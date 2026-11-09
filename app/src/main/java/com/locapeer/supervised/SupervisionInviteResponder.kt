package com.locapeer.supervised

import com.locapeer.crypto.CryptoUtils
import com.locapeer.crypto.KeyManager
import com.locapeer.nostr.NostrEvent
import com.locapeer.nostr.NostrEventKind
import com.locapeer.nostr.NostrRelayClient
import com.locapeer.settings.AppPreferences
import com.locapeer.util.backgroundScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
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
 * Sends the answer to a supervision invite (kind 1062) - decline, or a refusal because this
 * device is already supervised - so the supervisor's "Supervise" switch does not sit in
 * bought state forever.
 *
 * The answer is written to disk BEFORE it is published and cleared once the publish call
 * returns. A publish that throws (a build/encrypt error) or a process death right after the
 * notification action therefore leaves it to be replayed whenever a relay becomes reachable,
 * including at the next start.
 *
 * A successful publish only means the event was handed to [NostrRelayClient], which sends it
 * or keeps it in its persistent outbox until a relay takes it; no relay acknowledgement is
 * awaited here. The record covers what the outbox cannot: a process death before the
 * hand-off, or a build/encrypt failure.
 *
 * Accepting an invite does not go through this path: it sends a register request instead,
 * whose retry lives in SupervisedModeManager.
 */
@Singleton
class SupervisionInviteResponder @Inject constructor(
    private val keyManager: KeyManager,
    private val crypto: CryptoUtils,
    private val relayClient: NostrRelayClient,
    private val prefs: AppPreferences
) {
    /** A decision recorded before publishing, so an outage or a crash cannot lose it. */
    @Serializable
    data class PendingInviteResponse(
        val supervisorPubkey: String,
        val accepted: Boolean,
        val recordedAt: Long = 0
    )

    private val scope = backgroundScope("SupervisionInviteResponse")
    private val json = Json { ignoreUnknownKeys = true }
    /** Guards [pending]; mirrored to DataStore on every mutation. */
    private val queueMutex = Mutex()
    /** Serialises delivery passes; a decision and a reconnect can both trigger one. */
    private val deliverMutex = Mutex()
    private val pending = mutableListOf<PendingInviteResponse>()

    // Restoration must finish before a new decision is queued, otherwise the write could
    // overwrite persisted answers that have not been read yet.
    private val initialized = scope.async {
        queueMutex.withLock { loadPersistedLocked() }
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

    /**
     * Durable the answer, then try to send it. Safe to call from a broadcast receiver: the
     * record is made here, and anything left unsent stays owed across reconnects and restarts.
     */
    suspend fun respond(supervisorPubkey: String, accepted: Boolean) {
        try {
            record(supervisorPubkey, accepted)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Still attempt the send: losing durability must not also lose the reply.
            android.util.Log.w(TAG, "Failed to record supervision invite response", e)
        }
        deliverPending()
    }

    private suspend fun record(supervisorPubkey: String, accepted: Boolean) {
        initialized.await()
        queueMutex.withLock {
            // One answer per supervisor: a later decision replaces the earlier, unsent one
            // instead of sending a contradictory pair.
            pending.removeAll { it.supervisorPubkey == supervisorPubkey }
            pending.add(PendingInviteResponse(supervisorPubkey, accepted, System.currentTimeMillis()))
            while (pending.size > MAX_PENDING) pending.removeAt(0)
            persistLocked()
        }
    }

    private suspend fun deliverPending() {
        try {
            initialized.await()
            deliverMutex.withLock {
                val snapshot = queueMutex.withLock { pending.toList() }
                snapshot.forEach { response ->
                    try {
                        publish(response)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Keep the record: it is retried on the next reconnect or start.
                        android.util.Log.w(TAG, "Failed to publish supervision invite response", e)
                        return@forEach
                    }
                    queueMutex.withLock {
                        pending.remove(response)
                        persistLocked()
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Failed to deliver supervision invite responses", e)
        }
    }

    private suspend fun publish(response: PendingInviteResponse) {
        val (privHex, pubHex) = keyManager.ensureKeypair()
        val payload = json.encodeToString(
            SupervisedInviteResponsePayload(
                devicePubkeyHex = pubHex,
                accepted = response.accepted
            )
        )
        val encrypted = crypto.nip44Encrypt(
            crypto.hexToBytes(privHex), response.supervisorPubkey, payload
        )
        relayClient.publishEvent(
            NostrEvent.build(
                privKeyHex = privHex,
                pubKeyHex = pubHex,
                kind = NostrEventKind.SUPERVISED_INVITE_RESPONSE,
                content = encrypted,
                tags = listOf(listOf("p", response.supervisorPubkey)),
                crypto = crypto
            )
        )
    }

    /** Load answers a previous process had not sent yet; unreadable state and answers older
     *  than the invite notification they belong to are dropped rather than replayed forever. */
    private suspend fun loadPersistedLocked() {
        val raw = prefs.getPendingSupervisionInviteResponsesJson() ?: return
        val stored = runCatching {
            json.decodeFromString<List<PendingInviteResponse>>(raw)
        }.getOrDefault(emptyList())
        val cutoff = System.currentTimeMillis() - MAX_ANSWER_AGE_MS
        pending.clear()
        pending.addAll(stored.filter { it.recordedAt >= cutoff }.takeLast(MAX_PENDING))
        if (pending.size != stored.size || (pending.isEmpty() && raw.isNotBlank())) persistLocked()
    }

    private suspend fun persistLocked() {
        prefs.setPendingSupervisionInviteResponsesJson(
            if (pending.isEmpty()) null else json.encodeToString(pending.toList())
        )
    }

    private companion object {
        const val TAG = "SupervisionInviteResponse"

        /** An answer older than this belongs to an invitation the supervisor has given up on. */
        const val MAX_ANSWER_AGE_MS = 24 * 60 * 60_000L

        /** One per supervisor in practice; this only bounds a pathological backlog. */
        const val MAX_PENDING = 16
    }
}
