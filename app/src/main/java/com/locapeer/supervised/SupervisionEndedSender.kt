package com.locapeer.supervised

import android.util.Log
import com.locapeer.crypto.CryptoUtils
import com.locapeer.crypto.KeyManager
import com.locapeer.nostr.NostrEvent
import com.locapeer.nostr.NostrEventKind
import com.locapeer.nostr.NostrRelayClient
import com.locapeer.settings.AppPreferences
import com.locapeer.util.backgroundScope
import kotlinx.coroutines.CancellationException
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

private const val TAG = "SupervisionEnded"

/**
 * Sends the supervisor's "I stopped supervising you" notice (kind 1063) with a durable record,
 * so a notice whose publish failed - or that was interrupted by a process death right after the
 * switch was flipped - is sent again on the next start or reconnect.
 *
 * The notice matters more than a courtesy message: it is the only in-band signal the other
 * device has to clear its supervised state. One that never arrives leaves that device sharing
 * its location behind an unlock gate it can no longer answer, and the local switch already says
 * the supervision is over. The record is therefore written BEFORE publishing and kept, without
 * expiring, until the notice has been handed to the relay client; from there the client's
 * persistent outbox keeps it until a relay takes it, however long the device is offline. No
 * relay acknowledgement is awaited here.
 */
@Singleton
class SupervisionEndedSender @Inject constructor(
    private val prefs: AppPreferences,
    private val keyManager: KeyManager,
    private val crypto: CryptoUtils,
    private val relayClient: NostrRelayClient,
) {
    /** One unsent notice; the notice carries no state of its own beyond who it is for. */
    @Serializable
    data class PendingEndNotice(
        val peerPubkey: String,
        val peerRelay: String,
        /** When the notice was recorded; kept for ordering and diagnostics. */
        val recordedAt: Long = 0
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = backgroundScope("SupervisionEnded")
    private val mutex = Mutex()
    /** Serialises delivery passes: app start and a reconnect can fire at the same time. */
    private val retryMutex = Mutex()

    init {
        // Retry whenever a relay becomes reachable, not only at process start.
        scope.launch {
            relayClient.relayStatus
                .map { status -> status.values.any { connected -> connected } }
                .distinctUntilChanged()
                .filter { connected -> connected }
                .collect { retryPendingNow() }
        }
    }

    /** Record the notice, then try to send it. Safe to call from a UI action. */
    suspend fun send(peerPubkey: String, peerRelay: String) {
        deliver(record(peerPubkey, peerRelay))
    }

    /**
     * Drop a not-yet-published notice for [peerPubkey] because supervision was turned back on.
     * Left in place, a retry pass could publish it AFTER the fresh 1061 invite, and an ended
     * notice delivered after the invite is accepted clears the supervision the two devices
     * just re-established. A notice already handed to the relay client cannot be recalled,
     * but the outbox keeps publish order, so it reaches the device before the new invite.
     */
    suspend fun cancel(peerPubkey: String) {
        mutex.withLock { save(load().filterNot { it.peerPubkey == peerPubkey }) }
    }

    fun retryPending() {
        scope.launch { retryPendingNow() }
    }

    private suspend fun retryPendingNow() {
        retryMutex.withLock {
            mutex.withLock { load() }.forEach { notice -> deliver(notice) }
        }
    }

    private suspend fun record(peerPubkey: String, peerRelay: String): PendingEndNotice {
        val notice = PendingEndNotice(peerPubkey, peerRelay, System.currentTimeMillis())
        mutex.withLock {
            // Ending supervision twice for the same peer is still one notice; a queued
            // notice is refreshed rather than duplicated.
            val kept = load().filterNot { it.peerPubkey == peerPubkey } + notice
            save(if (kept.size > MAX_PENDING) kept.takeLast(MAX_PENDING) else kept)
        }
        return notice
    }

    private suspend fun deliver(notice: PendingEndNotice) {
        // A notice the user withdrew (by turning supervision back on) must not go out from a
        // pass that snapshotted it a moment earlier.
        if (!mutex.withLock { load().any { key(it) == key(notice) } }) return
        val published = try {
            publish(notice)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Keep the record: it is retried on the next start or reconnect.
            Log.w(TAG, "Failed to send supervision-ended notice", e)
            false
        }
        if (published) {
            mutex.withLock { save(load().filterNot { key(it) == key(notice) }) }
        }
    }

    private suspend fun publish(notice: PendingEndNotice) {
        val (privHex, pubHex) = keyManager.ensureKeypair()
        val payload = json.encodeToString(SupervisedSupervisorEndPayload(supervisorPubkeyHex = pubHex))
        val encrypted = crypto.nip44Encrypt(
            crypto.hexToBytes(privHex),
            notice.peerPubkey,
            payload
        )
        if (notice.peerRelay.isNotBlank()) relayClient.connect(notice.peerRelay)
        relayClient.publishEvent(
            NostrEvent.build(
                privKeyHex = privHex,
                pubKeyHex = pubHex,
                kind = NostrEventKind.SUPERVISED_SUPERVISOR_END,
                content = encrypted,
                tags = listOf(listOf("p", notice.peerPubkey)),
                crypto = crypto
            )
        )
    }

    private fun key(notice: PendingEndNotice) = "${notice.peerPubkey}:${notice.recordedAt}"

    /** Restore notices a previous process had not sent yet. */
    private suspend fun load(): List<PendingEndNotice> {
        val raw = prefs.getPendingSupervisionEndNoticesJson() ?: return emptyList()
        return runCatching {
            json.decodeFromString<List<PendingEndNotice>>(raw)
        }.getOrDefault(emptyList())
    }

    private suspend fun save(pending: List<PendingEndNotice>) {
        prefs.setPendingSupervisionEndNoticesJson(
            if (pending.isEmpty()) null else json.encodeToString(pending)
        )
    }

    private companion object {
        /** One per peer in practice; this only bounds a pathological backlog. */
        const val MAX_PENDING = 32
    }
}
