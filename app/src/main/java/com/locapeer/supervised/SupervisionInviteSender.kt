package com.locapeer.supervised

import android.util.Log
import com.locapeer.crypto.CryptoUtils
import com.locapeer.crypto.KeyManager
import com.locapeer.nostr.NostrEvent
import com.locapeer.nostr.NostrEventKind
import com.locapeer.nostr.NostrRelayClient
import com.locapeer.settings.AppPreferences
import com.locapeer.settings.HARDCODED_RELAYS
import com.locapeer.util.backgroundScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SupervisionInvite"

/**
 * Sends the supervisor's "may I supervise you?" invite (kind 1061) with a durable record, so
 * an invite whose publish failed - or that was interrupted by a process death right after the
 * switch was flipped - is sent at the next start or reconnect instead of being lost.
 *
 * The switch is written locally before the invite goes out, so a lost invite leaves this
 * device showing a supervision the other side was never asked to consent to and never sees.
 * The record is written BEFORE publishing and cleared once the invite has been handed to the
 * relay client, whose persistent outbox keeps it until a relay takes it (no relay
 * acknowledgement is awaited here).
 *
 * Turning the switch back off drops a record that has not been published yet ([cancel]). It
 * cannot recall an invite already handed to the relay client; that one is followed by the
 * ended notice, which the outbox delivers after it, so the peer still ends up unsupervised.
 */
@Singleton
class SupervisionInviteSender @Inject constructor(
    private val prefs: AppPreferences,
    private val keyManager: KeyManager,
    private val crypto: CryptoUtils,
    private val relayClient: NostrRelayClient,
) {
    /** An invite owed to a peer; one per peer, refreshed by a later invite. */
    @Serializable
    data class PendingInvite(
        val peerPubkey: String,
        val peerRelay: String,
        /** When the invite was recorded; 0 for a record written by an older build. */
        val recordedAt: Long = 0
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = backgroundScope("SupervisionInvite")
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

    /** Record the invite, then try to send it. */
    suspend fun send(peerPubkey: String, peerRelay: String) {
        deliver(record(peerPubkey, peerRelay))
    }

    /** Drop a not-yet-published invite for [peerPubkey] (the user turned the switch back off). */
    suspend fun cancel(peerPubkey: String) {
        mutex.withLock { save(load().filterNot { it.peerPubkey == peerPubkey }) }
    }

    fun retryPending() {
        scope.launch { retryPendingNow() }
    }

    private suspend fun retryPendingNow() {
        retryMutex.withLock {
            mutex.withLock { load() }.forEach { invite -> deliver(invite) }
        }
    }

    private suspend fun record(peerPubkey: String, peerRelay: String): PendingInvite {
        val invite = PendingInvite(peerPubkey, peerRelay, System.currentTimeMillis())
        mutex.withLock {
            save(load().filterNot { it.peerPubkey == peerPubkey } + invite)
        }
        return invite
    }

    private suspend fun deliver(invite: PendingInvite) {
        // A withdrawn invitation must not go out from a pass that snapshotted it a moment
        // before the user turned the switch back off. The load above suspends on DataStore,
        // so a cancel can land between the snapshot and this publish.
        if (!mutex.withLock { load().any { it.peerPubkey == invite.peerPubkey && it.recordedAt == invite.recordedAt } }) {
            return
        }
        val published = try {
            publish(invite)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Keep the record: it is retried on the next start or reconnect.
            Log.w(TAG, "Failed to send supervision invite", e)
            false
        }
        if (published) {
            mutex.withLock {
                save(load().filterNot {
                    it.peerPubkey == invite.peerPubkey && it.recordedAt == invite.recordedAt
                })
            }
        }
    }

    private suspend fun publish(invite: PendingInvite) {
        val (privHex, pubHex) = keyManager.ensureKeypair()
        val settings = prefs.settings.first()
        val myRelay = relayClient.relayStatus.value.filter { it.value }.keys.firstOrNull()
            ?: HARDCODED_RELAYS.first()
        val payload = json.encodeToString(
            SupervisedInvitePayload(
                supervisorName = settings.displayName.ifBlank { pubHex.take(8) },
                supervisorRelayUrl = myRelay
            )
        )
        val encrypted = crypto.nip44Encrypt(
            crypto.hexToBytes(privHex),
            invite.peerPubkey,
            payload
        )
        if (invite.peerRelay.isNotBlank()) relayClient.connect(invite.peerRelay)
        relayClient.publishEvent(
            NostrEvent.build(
                privKeyHex = privHex,
                pubKeyHex = pubHex,
                kind = NostrEventKind.SUPERVISED_INVITE,
                content = encrypted,
                tags = listOf(listOf("p", invite.peerPubkey)),
                crypto = crypto
            )
        )
    }

    /**
     * Restore invites a previous process had not sent yet, dropping the ones past their expiry:
     * an invitation this old would ask the device to consent to a supervision the supervisor
     * has long forgotten about. A record without a timestamp is stamped now.
     */
    private suspend fun load(): List<PendingInvite> {
        val raw = prefs.getPendingSupervisionInvitesJson() ?: return emptyList()
        val stored = runCatching {
            json.decodeFromString<List<PendingInvite>>(raw)
        }.getOrDefault(emptyList())
        val now = System.currentTimeMillis()
        val kept = stored
            .map { if (it.recordedAt <= 0) it.copy(recordedAt = now) else it }
            .filter { now - it.recordedAt <= MAX_INVITE_AGE_MS }
            .takeLast(MAX_PENDING)
        if (kept != stored) save(kept)
        return kept
    }

    private suspend fun save(pending: List<PendingInvite>) {
        prefs.setPendingSupervisionInvitesJson(
            if (pending.isEmpty()) null else json.encodeToString(pending)
        )
    }

    private companion object {
        /** An invite older than this proposes a supervision the user has moved past. */
        const val MAX_INVITE_AGE_MS = 24 * 60 * 60_000L

        /** One per peer in practice; this only bounds a pathological backlog. */
        const val MAX_PENDING = 32
    }
}
