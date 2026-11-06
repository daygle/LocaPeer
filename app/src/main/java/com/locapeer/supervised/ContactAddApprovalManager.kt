package com.locapeer.supervised

import com.locapeer.crypto.CryptoUtils
import com.locapeer.crypto.KeyManager
import com.locapeer.invite.ContactAddExecutor
import com.locapeer.invite.InviteData
import com.locapeer.nostr.NostrEvent
import com.locapeer.nostr.NostrEventKind
import com.locapeer.nostr.NostrRelayClient
import com.locapeer.settings.AppPreferences
import com.locapeer.util.backgroundScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

internal const val CONTACT_ADD_TIMEOUT_MS = 60_000L

internal fun contactAddTimeoutDelayMs(requestedAt: Long, nowMillis: Long): Long =
    (requestedAt + CONTACT_ADD_TIMEOUT_MS - nowMillis).coerceAtLeast(0L)

/**
 * Per-action approval queue for adding NEW tracking destinations while supervised
 * (kinds 1067/1068). Both doors a supervised device has for establishing new shares
 * flow through here:
 *
 *  - [com.locapeer.invite.ScanViewModel.confirmAdd] (outbound: scanning someone's invite),
 *  - [com.locapeer.invite.PendingRequestActions.accept] (inbound: accepting their track
 *    request after they scanned this device's invite).
 *
 * The queue supports SEVERAL simultaneous decisions (they run independently), and the
 * intents are persisted in DataStore so a decision that lands right after a process kill
 * can still complete: the supervisor's answer window is what bounds the wait, not the
 * lifetime of a screen. Each queued entry carries its own 60 s reminder timeout; entries
 * older than the supervisor's 5-min freshness window are pruned.
 */
@Singleton
class ContactAddApprovalManager @Inject constructor(
    private val keyManager: KeyManager,
    private val crypto: CryptoUtils,
    private val relayClient: NostrRelayClient,
    private val prefs: AppPreferences,
    private val executor: ContactAddExecutor
) {
    /** What to DO when the supervisor approves. Serialized with the queue for durability. */
    @kotlinx.serialization.Serializable
    data class StoredAccept(
        val senderPubkey: String,
        val locationRole: String,
        val messagingEnabled: Boolean
    )

    @kotlinx.serialization.Serializable
    data class StoredContactAddIntent(
        val requestId: String,
        val contactPubkeyHex: String,
        val contactName: String,
        /** Outbound scan/invite: the full staged invite. */
        val scan: InviteData? = null,
        /** Inbound track-request accept: the review screen's chosen role/messaging. */
        val accept: StoredAccept? = null,
        val requestedAt: Long = 0
    )

    sealed interface AddAction {
        data class OutboundScan(val invite: InviteData) : AddAction
        data class InboundAccept(
            val senderPubkey: String,
            val locationRole: String,
            val messagingEnabled: Boolean
        ) : AddAction
    }

    data class PendingAdd(
        val requestId: String,
        val contactPubkeyHex: String,
        val contactName: String,
        val requestedAt: Long
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = backgroundScope("ContactAddApproval")
    private val mutex = Mutex()

    /** The live queue; mirrored to DataStore on every mutation. */
    private val queue = mutableListOf<StoredContactAddIntent>()
    private val _pending = MutableStateFlow<List<PendingAdd>>(emptyList())
    val pending: StateFlow<List<PendingAdd>> = _pending.asStateFlow()

    /** UI-facing decision callbacks, keyed by requestId. In-memory by design: they only
     *  update screen state; the durable effect (the add itself) lives in the intent. */
    private val callbacks = HashMap<String, (Boolean) -> Unit>()

    private val timeouts = HashMap<String, Job>()

    init {
        scope.launch {
            mutex.withLock {
                loadPersistedLocked().forEach { intent ->
                    armTimeout(intent.requestId, timeoutDelayMs(intent.requestedAt))
                }
            }
            publish()
        }
    }

    /**
     * Queue a contact-add decision for the supervisor, invoking [onDecided]
     * (false → denied or timed out, true → queued entry completed) on the outcome.
     * A new request for the SAME contact supersedes the previous one.
     */
    suspend fun requestAdd(
        contactPubkeyHex: String,
        contactName: String,
        action: AddAction,
        onDecided: (Boolean) -> Unit
    ) {
        val settings = prefs.settings.first()
        val supervisorPubkey = settings.supervisorPubkey
        if (!settings.supervisionActive || supervisorPubkey.isEmpty()) {
            // Condition changed since the caller checked: behave as unsupervised - the
            // caller's normal un-gated path proceeds.
            onDecided(true)
            return
        }

        val requestId = UUID.randomUUID().toString()
        val intent = StoredContactAddIntent(
            requestId = requestId,
            contactPubkeyHex = contactPubkeyHex,
            contactName = contactName.take(64),
            scan = (action as? AddAction.OutboundScan)?.invite,
            accept = (action as? AddAction.InboundAccept)?.let {
                StoredAccept(it.senderPubkey, it.locationRole, it.messagingEnabled)
            },
            requestedAt = System.currentTimeMillis()
        )

        var supersededCallbacks = emptyList<(Boolean) -> Unit>()
        mutex.withLock {
            // Same contact => supersede (the supervisor answers the newest request only;
            // a stale duplicate would double-fire the physical add).
            val superseded = queue.filter {
                it.contactPubkeyHex.equals(contactPubkeyHex, ignoreCase = true)
            }
            superseded.forEach { timeouts.remove(it.requestId)?.cancel() }
            supersededCallbacks = superseded.mapNotNull { callbacks.remove(it.requestId) }
            queue.removeAll {
                it.contactPubkeyHex.equals(contactPubkeyHex, ignoreCase = true) ||
                    it.requestId == requestId
            }
            callbacks[requestId] = onDecided
            queue.add(intent)
            persistLocked()
            armTimeout(requestId, timeoutDelayMs(intent.requestedAt))
        }
        supersededCallbacks.forEach { callback -> runCatching { callback(false) } }
        publish()

        try {
            val (privHex, pubHex) = keyManager.ensureKeypair()
            val payload = json.encodeToString(
                ContactAddRequestPayload(
                    requestId = requestId,
                    contactPubkeyHex = contactPubkeyHex,
                    contactName = intent.contactName
                )
            )
            val encrypted = crypto.nip44Encrypt(crypto.hexToBytes(privHex), supervisorPubkey, payload)
            relayClient.publishEvent(
                NostrEvent.build(
                    privKeyHex = privHex,
                    pubKeyHex = pubHex,
                    kind = NostrEventKind.CONTACT_ADD_REQUEST,
                    content = encrypted,
                    tags = listOf(listOf("p", supervisorPubkey)),
                    crypto = crypto
                )
            )
        } catch (e: Exception) {
            // No path to the supervisor: treat as a denial so nothing treats silence as
            // consent; the queued intent is dropped with it.
            android.util.Log.w(TAG, "Failed to send contact-add request", e)
            removeFromQueue(requestId, decided = false)
        }
    }

    /** The supervisor's kind-1068 answer (delivered by HeartbeatReceiver). */
    fun handleResponse(requestId: String, approved: Boolean) {
        scope.launch {
            val intent = removeFromQueue(requestId, decided = approved) ?: return@launch
            if (!approved) return@launch
            // Run the durable effect on our own scope: works with no screen attached
            // (backgrounded app, or a decision arriving right after process death with
            // the intent loaded from DataStore).
            runCatching { executeLocked(intent) }
                .onFailure { android.util.Log.w(TAG, "Approved contact add failed to complete", it) }
        }
    }

    /** The physical add, from a stored intent (executor owns both paths). */
    private suspend fun executeLocked(intent: StoredContactAddIntent) {
        when {
            intent.scan != null -> executor.performOutboundAdd(intent.scan)
            intent.accept != null -> executor.completeInboundAccept(
                intent.accept.senderPubkey,
                intent.accept.locationRole,
                intent.accept.messagingEnabled
            )
        }
    }

    /** Withdraw a pending decision for [contactPubkeyHex] (screen cancelled/reset). */
    suspend fun cancelPending(contactPubkeyHex: String) {
        removeFromQueueFor(contactPubkeyHex)
    }

    private suspend fun removeFromQueue(
        requestId: String,
        decided: Boolean?,
        fromTimeout: Boolean = false
    ): StoredContactAddIntent? {
        val removed: StoredContactAddIntent?
        val timeout: Job?
        val callback: ((Boolean) -> Unit)?
        mutex.withLock {
            removed = queue.firstOrNull { it.requestId == requestId }
            timeout = timeouts.remove(requestId)
            callback = callbacks.remove(requestId)
            if (removed != null) {
                queue.removeAll { it.requestId == requestId }
                persistLocked()
            }
        }
        if (removed != null) {
            if (!fromTimeout) timeout?.cancel()
            callback?.let { onDecided ->
                decided?.let { result -> runCatching { onDecided(result) } }
            }
            publish()
        }
        return removed
    }

    private suspend fun removeFromQueueFor(contactPubkeyHex: String) {
        var removedCallbacks = emptyList<(Boolean) -> Unit>()
        mutex.withLock {
            val removed = queue.filter { it.contactPubkeyHex.equals(contactPubkeyHex, ignoreCase = true) }
            removed.forEach { timeouts.remove(it.requestId)?.cancel() }
            removedCallbacks = removed.mapNotNull { callbacks.remove(it.requestId) }
            queue.removeAll { it.contactPubkeyHex.equals(contactPubkeyHex, ignoreCase = true) }
            persistLocked()
        }
        removedCallbacks.forEach { callback -> runCatching { callback(false) } }
        publish()
    }


    private fun armTimeout(requestId: String, delayMs: Long = CONTACT_ADD_TIMEOUT_MS) {
        timeouts[requestId]?.cancel()
        timeouts[requestId] = scope.launch {
            delay(delayMs)
            removeFromQueue(requestId, decided = false, fromTimeout = true)
        }
    }

    private fun timeoutDelayMs(requestedAt: Long): Long =
        contactAddTimeoutDelayMs(requestedAt, System.currentTimeMillis())

    /** Load persisted intents at construction; drop anything older than the supervisor's
     *  decision freshness window (they can no longer be answered, only re-requested). */
    private suspend fun loadPersistedLocked(): List<StoredContactAddIntent> {
        val raw = prefs.getContactAddIntentsJson() ?: return emptyList()
        val stored = runCatching {
            json.decodeFromString<List<StoredContactAddIntent>>(raw)
        }.getOrDefault(emptyList())
        val cutoff = System.currentTimeMillis() - 290_000 // just under the 5-min freshness
        queue.clear()
        queue.addAll(stored.filter { it.requestedAt >= cutoff })
        if (queue.size != stored.size) persistLocked()
        return queue.toList()
    }

    private suspend fun persistLocked() {
        prefs.setContactAddIntentsJson(
            if (queue.isEmpty()) null else json.encodeToString(queue.toList())
        )
    }

    /** All queue mutations happen under [mutex]; snapshot under the same lock so the
     *  plain list copy can't race a concurrent edit. */
    private suspend fun publish() {
        val snapshot = mutex.withLock { queue.toList() }
        _pending.value = snapshot.map { intent ->
            PendingAdd(
                requestId = intent.requestId,
                contactPubkeyHex = intent.contactPubkeyHex,
                contactName = intent.contactName,
                requestedAt = intent.requestedAt
            )
        }
    }

    private companion object {
        const val TAG = "ContactAddApproval"
    }
}
