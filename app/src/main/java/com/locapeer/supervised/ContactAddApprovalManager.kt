package com.locapeer.supervised

import com.locapeer.crypto.CryptoUtils
import com.locapeer.crypto.KeyManager
import com.locapeer.data.entity.PendingRequestEntity
import com.locapeer.invite.ContactAddExecutor
import com.locapeer.invite.InviteData
import com.locapeer.nostr.NostrEvent
import com.locapeer.nostr.NostrEventKind
import com.locapeer.nostr.NostrRelayClient
import com.locapeer.settings.AppPreferences
import com.locapeer.util.backgroundScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
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

/**
 * How long a queued add stays answerable. It must outlive the window in which
 * [com.locapeer.subscriber.HeartbeatReceiver] still accepts the supervisor's answer (300 s)
 * plus relay slack: pruning the intent before its answer arrives makes an approved add
 * vanish silently, while the supervisor's device shows the approval as done. The old 60 s
 * timeout did exactly that for any supervisor who took longer than a minute.
 */
internal const val CONTACT_ADD_TIMEOUT_MS = 360_000L

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
 * lifetime of a screen. Each queued entry times out after [CONTACT_ADD_TIMEOUT_MS], which
 * is deliberately longer than the answer window the receiver accepts.
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
        val messagingEnabled: Boolean,
        // Null for older queued entries: these cannot safely authorize a particular request.
        val request: PendingRequestEntity? = null
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

    enum class AddResult { COMPLETED, DENIED, FAILED }

    sealed interface AddAction {
        data class OutboundScan(val invite: InviteData) : AddAction
        data class InboundAccept(
            val request: PendingRequestEntity,
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
    private val callbacks = HashMap<String, (AddResult) -> Unit>()
    private val claimed = HashSet<String>()

    private val timeouts = HashMap<String, Job>()

    // All queue operations await restoration; otherwise a new write or a response can win
    // the mutex before the saved queue has been read and silently discard pending decisions.
    private val initialization = scope.async {
        mutex.withLock {
            loadPersistedLocked().forEach { intent ->
                armTimeout(intent.requestId, timeoutDelayMs(intent.requestedAt))
            }
        }
        publish()
    }

    /**
     * Queue a contact-add decision for the supervisor, invoking [onDecided]
     * with the completed, denied/timed-out, or failed outcome.
     * A new request for the SAME contact supersedes the previous one.
     */
    suspend fun requestAdd(
        contactPubkeyHex: String,
        contactName: String,
        action: AddAction,
        onDecided: (AddResult) -> Unit
    ) {
        initialization.await()
        val settings = prefs.settings.first()
        val supervisorPubkey = settings.supervisorPubkey
        if (!settings.supervisionActive || supervisorPubkey.isEmpty()) {
            // The caller already delegated the action to us. If supervision ended between
            // checks, perform it here before reporting success rather than losing the add.
            val completed = when (action) {
                is AddAction.OutboundScan -> executor.performOutboundAdd(action.invite)
                is AddAction.InboundAccept -> executor.completeInboundAccept(
                    action.request, action.locationRole, action.messagingEnabled
                )
            }
            onDecided(if (completed) AddResult.COMPLETED else AddResult.FAILED)
            return
        }

        val requestId = UUID.randomUUID().toString()
        val intent = StoredContactAddIntent(
            requestId = requestId,
            contactPubkeyHex = contactPubkeyHex,
            contactName = contactName.take(64),
            scan = (action as? AddAction.OutboundScan)?.invite,
            accept = (action as? AddAction.InboundAccept)?.let {
                StoredAccept(it.request.senderPubkey, it.locationRole, it.messagingEnabled, it.request)
            },
            requestedAt = System.currentTimeMillis()
        )

        var supersededCallbacks = emptyList<(AddResult) -> Unit>()
        var alreadyCompleting = false
        mutex.withLock {
            if (queue.any { it.requestId in claimed && it.contactPubkeyHex.equals(contactPubkeyHex, ignoreCase = true) }) {
                alreadyCompleting = true
                return@withLock
            }
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
        if (alreadyCompleting) {
            onDecided(AddResult.FAILED)
            return
        }
        supersededCallbacks.forEach { callback -> runCatching { callback(AddResult.DENIED) } }
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
            removeFromQueue(requestId, decided = AddResult.DENIED)
        }
    }

    /** The supervisor's kind-1068 answer (delivered by HeartbeatReceiver). */
    fun handleResponse(requestId: String, approved: Boolean) {
        scope.launch {
            initialization.await()
            if (!approved) {
                removeFromQueue(requestId, decided = AddResult.DENIED)
                return@launch
            }
            val intent = mutex.withLock {
                val queued = queue.firstOrNull { it.requestId == requestId }
                if (queued == null || !claimed.add(requestId)) null else {
                    timeouts.remove(requestId)?.cancel()
                    queued
                }
            } ?: return@launch
            // Keep the intent persisted until execution completes; duplicate responses and
            // timeouts cannot claim it while the physical add is running.
            val completed = try {
                executeLocked(intent)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Approved contact add failed to complete", e)
                false
            }
            removeFromQueue(requestId, decided = if (completed) AddResult.COMPLETED else AddResult.FAILED, finishClaimed = true)
        }
    }

    /** The physical add, from a stored intent (executor owns both paths). */
    private suspend fun executeLocked(intent: StoredContactAddIntent): Boolean =
        when {
            intent.scan != null -> executor.performOutboundAdd(intent.scan)
            intent.accept?.request != null && intent.accept.senderPubkey == intent.accept.request.senderPubkey ->
                executor.completeInboundAccept(
                    intent.accept.request,
                    intent.accept.locationRole,
                    intent.accept.messagingEnabled
                )
            else -> false
        }

    /** Withdraw a pending decision for [contactPubkeyHex] (screen cancelled/reset). */
    suspend fun cancelPending(contactPubkeyHex: String) {
        initialization.await()
        removeFromQueueFor(contactPubkeyHex)
    }

    private suspend fun removeFromQueue(
        requestId: String,
        decided: AddResult?,
        fromTimeout: Boolean = false,
        finishClaimed: Boolean = false
    ): StoredContactAddIntent? {
        var removed: StoredContactAddIntent? = null
        var timeout: Job? = null
        var callback: ((AddResult) -> Unit)? = null
        mutex.withLock {
            if (requestId in claimed && !finishClaimed) return@withLock
            if (finishClaimed) claimed.remove(requestId)
            removed = queue.firstOrNull { it.requestId == requestId }
            if (removed == null) return@withLock
            timeout = timeouts.remove(requestId)
            callback = callbacks.remove(requestId)
            queue.removeAll { it.requestId == requestId }
            persistLocked()
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
        var removedCallbacks = emptyList<(AddResult) -> Unit>()
        mutex.withLock {
            val removed = queue.filter { it.requestId !in claimed && it.contactPubkeyHex.equals(contactPubkeyHex, ignoreCase = true) }
            removed.forEach { timeouts.remove(it.requestId)?.cancel() }
            removedCallbacks = removed.mapNotNull { callbacks.remove(it.requestId) }
            queue.removeAll(removed.toSet())
            persistLocked()
        }
        removedCallbacks.forEach { callback -> runCatching { callback(AddResult.DENIED) } }
        publish()
    }


    private fun armTimeout(requestId: String, delayMs: Long = CONTACT_ADD_TIMEOUT_MS) {
        timeouts[requestId]?.cancel()
        timeouts[requestId] = scope.launch {
            delay(delayMs)
            removeFromQueue(requestId, decided = AddResult.DENIED, fromTimeout = true)
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
        val cutoff = System.currentTimeMillis() - CONTACT_ADD_TIMEOUT_MS
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
        mutex.withLock {
            _pending.value = queue.map { intent ->
                PendingAdd(
                    requestId = intent.requestId,
                    contactPubkeyHex = intent.contactPubkeyHex,
                    contactName = intent.contactName,
                    requestedAt = intent.requestedAt
                )
            }
        }
    }

    private companion object {
        const val TAG = "ContactAddApproval"
    }
}
