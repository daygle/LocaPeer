package com.locapeer.supervised

import com.locapeer.crypto.CryptoUtils
import com.locapeer.util.backgroundScope
import com.locapeer.crypto.KeyManager
import com.locapeer.data.dao.PeerSharingConfigDao
import com.locapeer.nostr.NostrEvent
import com.locapeer.nostr.NostrEventKind
import com.locapeer.nostr.NostrRelayClient
import com.locapeer.settings.AppPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Supervisor side of supervised-mode approvals: what the supervised device is asking for
 * (unlock its settings session, release supervised mode entirely, or add a new tracking
 * destination) and the answer the supervisor gives it.
 *
 * A decision is written to disk BEFORE it is published. An answer that fails to publish -
 * a build/encrypt error, a process death between the click and the send - therefore survives
 * and is replayed whenever a relay becomes reachable, including at the next start. With no
 * relay connected the publish itself still succeeds: the relay client keeps the event in its
 * persistent outbox, and no relay acknowledgement is awaited here. Without that record the supervisor's click was silently lost while the
 * supervised device sat waiting for an answer that would never come.
 */
@Singleton
class SupervisionApprovalManager @Inject constructor(
    private val keyManager: KeyManager,
    private val crypto: CryptoUtils,
    private val relayClient: NostrRelayClient,
    private val prefs: AppPreferences,
    private val sharingConfigDao: PeerSharingConfigDao
) {
    /** What the supervised device is asking for: unlock its settings session, release
     *  supervised mode entirely, or add a new tracking destination. Same approval surface,
     *  different response kind. */
    @Serializable
    enum class RequestType { UNLOCK, DISABLE, CONTACT_ADD }

    @Serializable
    data class PendingRequest(
        val fromPubkey: String,
        val deviceName: String,
        val requestId: String,
        val type: RequestType = RequestType.UNLOCK
    )

    /** A decision recorded before publishing, so an outage or a crash cannot lose it. */
    @Serializable
    data class StoredAnswer(
        val request: PendingRequest,
        val approved: Boolean,
        val recordedAt: Long = 0
    )

    private val _pending = MutableStateFlow<PendingRequest?>(null)
    val pending: StateFlow<PendingRequest?> = _pending.asStateFlow()
    private val pendingLock = Any()
    private val waiting = ArrayDeque<PendingRequest>()
    /** Requests already answered in this process, oldest first. The supervised device resends
     *  an unanswered request with the same id when a relay reconnects, and the original may
     *  also still arrive from an outbox; neither may open a second dialog for an answered one. */
    private val answeredKeys = LinkedHashSet<String>()

    private val scope = backgroundScope("SupervisionApproval")
    private val json = Json { ignoreUnknownKeys = true }
    /** Guards [answers]; mirrored to DataStore on every mutation. */
    private val answerMutex = Mutex()
    /** Serialises delivery passes; a click and a reconnect can both trigger one. */
    private val deliverMutex = Mutex()
    private val answers = mutableListOf<StoredAnswer>()

    // Restoration must finish before a new answer is queued, otherwise the write could
    // overwrite persisted answers that have not been read yet.
    private val initialized = scope.async {
        answerMutex.withLock { loadPersistedLocked() }
    }

    init {
        // Retry recorded answers whenever a relay becomes reachable, not only at app start.
        // StateFlow replays its current status, so a relay that is already connected at
        // construction also delivers immediately.
        scope.launch {
            relayClient.relayStatus
                .map { status -> status.values.any { connected -> connected } }
                .distinctUntilChanged()
                .filter { connected -> connected }
                .collect { deliverPending() }
        }
    }

    fun setPending(request: PendingRequest) = synchronized(pendingLock) {
        if (answerKey(request) in answeredKeys) return@synchronized
        val current = _pending.value
        val duplicate = (listOfNotNull(current) + waiting).any {
            it.fromPubkey == request.fromPubkey && it.type == request.type && it.requestId == request.requestId
        }
        // Bound memory if supervised contacts flood the supervisor. Dropped requests time out
        // on the requesting device rather than replacing an approval already being displayed.
        if (!duplicate && waiting.size + (if (current == null) 0 else 1) < 64) {
            if (current == null) _pending.value = request else waiting.addLast(request)
        }
    }

    /** Claim only the request shown by the dialog, ignoring stale or repeated clicks. */
    internal fun claimPending(request: PendingRequest): Boolean = synchronized(pendingLock) {
        if (_pending.value != request) return@synchronized false
        _pending.value = waiting.removeFirstOrNull()
        answeredKeys += answerKey(request)
        while (answeredKeys.size > MAX_ANSWERED_KEYS) answeredKeys.remove(answeredKeys.first())
        true
    }

    private fun answerKey(request: PendingRequest) =
        "${request.fromPubkey}:${request.type}:${request.requestId}"

    internal suspend fun applyApprovedRelease(request: PendingRequest, approved: Boolean) {
        if (approved && request.type == RequestType.DISABLE) {
            sharingConfigDao.clearSupervision(request.fromPubkey)
        }
    }

    fun respond(request: PendingRequest, approved: Boolean) {
        if (!claimPending(request)) return
        scope.launch {
            // Record first: the answer must survive anything that happens before it is sent.
            try {
                recordAnswer(request, approved)
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Failed to record supervision answer", e)
            }
            deliverPending()
        }
    }

    private suspend fun recordAnswer(request: PendingRequest, approved: Boolean) {
        initialized.await()
        answerMutex.withLock {
            if (answers.none { it.request.requestId == request.requestId }) {
                answers.add(StoredAnswer(request, approved, System.currentTimeMillis()))
                persistLocked()
            }
        }
    }

    private suspend fun deliverPending() {
        try {
            initialized.await()
            deliverMutex.withLock {
                val snapshot = answerMutex.withLock { answers.toList() }
                snapshot.forEach { answer ->
                    try {
                        publishAnswer(answer)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Keep the record: they are retried on the next reconnect or start.
                        android.util.Log.w(TAG, "Failed to publish supervision answer", e)
                        return@forEach
                    }
                    try {
                        applyApprovedRelease(answer.request, answer.approved)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // The answer is already out; a duplicate is harmless, so keep the
                        // record and retry the release alongside it.
                        android.util.Log.w(TAG, "Failed to apply supervision answer", e)
                        return@forEach
                    }
                    answerMutex.withLock {
                        answers.remove(answer)
                        persistLocked()
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Failed to deliver supervision answers", e)
        }
    }

    /** Publish BEFORE the supervisor drops its own supervision flag. If the publish fails,
     *  both sides remain supervised - consistent, and the record above retries - whereas
     *  clearing first would leave the device gated with nobody left to answer it. */
    private suspend fun publishAnswer(answer: StoredAnswer) {
        val request = answer.request
        val (privHex, pubHex) = keyManager.ensureKeypair()
        val payload: String
        val kind: Int
        when (request.type) {
            RequestType.UNLOCK -> {
                payload = json.encodeToString(
                    UnlockResponsePayload(requestId = request.requestId, approved = answer.approved)
                )
                kind = NostrEventKind.SUPERVISED_UNLOCK_RESPONSE
            }
            RequestType.DISABLE -> {
                payload = json.encodeToString(
                    SupervisedDisableResponsePayload(requestId = request.requestId, approved = answer.approved)
                )
                kind = NostrEventKind.SUPERVISED_DISABLE_RESPONSE
            }
            RequestType.CONTACT_ADD -> {
                payload = json.encodeToString(
                    ContactAddResponsePayload(requestId = request.requestId, approved = answer.approved)
                )
                kind = NostrEventKind.CONTACT_ADD_RESPONSE
            }
        }
        val encrypted = crypto.nip44Encrypt(crypto.hexToBytes(privHex), request.fromPubkey, payload)
        val event = NostrEvent.build(
            privKeyHex = privHex,
            pubKeyHex = pubHex,
            kind = kind,
            content = encrypted,
            tags = listOf(listOf("p", request.fromPubkey)),
            crypto = crypto
        )
        relayClient.publishEvent(event)
    }

    /** Load answers a previous process had not sent yet; unreadable state and answers older
     *  than the request windows they belong to are dropped rather than replayed forever. */
    private suspend fun loadPersistedLocked() {
        val raw = prefs.getPendingSupervisionAnswersJson() ?: return
        val stored = runCatching {
            json.decodeFromString<List<StoredAnswer>>(raw)
        }.getOrDefault(emptyList())
        val cutoff = System.currentTimeMillis() - MAX_ANSWER_AGE_MS
        answers.clear()
        answers.addAll(stored.filter { it.recordedAt >= cutoff }.take(MAX_STORED_ANSWERS))
        if (answers.size != stored.size || (answers.isEmpty() && raw.isNotBlank())) persistLocked()
    }

    private suspend fun persistLocked() {
        prefs.setPendingSupervisionAnswersJson(
            if (answers.isEmpty()) null else json.encodeToString(answers.toList())
        )
    }

    private companion object {
        const val TAG = "SupervisionApproval"

        /** Answers older than this belong to requests the other side has given up on. */
        const val MAX_ANSWER_AGE_MS = 24 * 60 * 60_000L

        /** Answered requests remembered for duplicate suppression; far more than are live. */
        const val MAX_ANSWERED_KEYS = 256

        /** Backstop in case answers pile up across many crashes while offline. */
        const val MAX_STORED_ANSWERS = 64
    }
}
