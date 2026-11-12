package com.locapeer.invite

import android.content.Context
import android.util.Log
import com.locapeer.R
import com.locapeer.crypto.CryptoUtils
import com.locapeer.crypto.KeyManager
import com.locapeer.nostr.NostrEvent
import com.locapeer.nostr.NostrEventKind
import com.locapeer.nostr.NostrRelayClient
import com.locapeer.settings.AppPreferences
import com.locapeer.settings.HARDCODED_RELAYS
import com.locapeer.util.backgroundScope
import dagger.hilt.android.qualifiers.ApplicationContext
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

private const val TAG = "TrackResponse"

/**
 * Builds and publishes TRACK_ACCEPT / TRACK_DECLINE responses. One implementation for
 * the places that answer a track request: the incoming-request screen, the pending
 * requests list, the notification decline action, and HeartbeatReceiver's auto-promote.
 *
 * The answer is recorded before it is published and kept until it has been handed to the
 * relay client, so a reply that could not be built (a key or crypto failure) or was cut off by
 * a process death right after the request was cleared is retried at the next start or
 * reconnect instead of being dropped. Once handed over, the client's persistent outbox keeps
 * it until a relay takes it; no relay acknowledgement is awaited here.
 * Both answers matter: an accept is the requester's only way of learning the add landed, and a
 * decline is the only way of learning it did not. One answer per peer, so a later decision
 * replaces an earlier unsent one instead of delivering a contradictory pair.
 */
@Singleton
class TrackResponseSender @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keyManager: KeyManager,
    private val crypto: CryptoUtils,
    private val relayClient: NostrRelayClient,
    private val prefs: AppPreferences,
) {
    /** An answer owed to a requester; [acceptedRole] is null for a decline. */
    @Serializable
    data class PendingResponse(
        val peerPubkey: String,
        val peerRelay: String,
        /** The role granted by an accept, or null when this is a decline. */
        val acceptedRole: String? = null,
        /** Decline only: tells the requester whether to keep the contact. */
        val isRoleChange: Boolean = false,
        /** When the answer was recorded; 0 for a record written by an older build. */
        val recordedAt: Long = 0
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = backgroundScope("TrackResponse")
    private val mutex = Mutex()
    /** Serialises retry passes: app start and a reconnect can fire at the same time. */
    private val retryMutex = Mutex()

    init {
        // Retry owed answers whenever a relay becomes reachable, not only at start: the
        // requester should not have to wait for another action by this user.
        scope.launch {
            relayClient.relayStatus
                .map { status -> status.values.any { connected -> connected } }
                .distinctUntilChanged()
                .filter { connected -> connected }
                .collect { retryPendingNow() }
        }
    }

    /**
     * Publish a TRACK_ACCEPT granting [acceptedRole], connecting to [recipientRelay]
     * first (when known) so the response reaches the requester's relay. A publish that
     * fails does not throw: the answer stays queued and is resent on its own.
     */
    suspend fun sendAccept(recipientPubkey: String, recipientRelay: String, acceptedRole: String) {
        answer(
            PendingResponse(
                peerPubkey = recipientPubkey,
                peerRelay = recipientRelay,
                acceptedRole = acceptedRole,
                recordedAt = System.currentTimeMillis()
            )
        )
    }

    /**
     * Publish a TRACK_DECLINE; [isRoleChange] tells the requester whether to keep the contact.
     * Durable for the same reason as an accept: a decline dropped on a failed publish leaves
     * the requester waiting on an answer that will never come.
     */
    suspend fun sendDecline(recipientPubkey: String, recipientRelay: String, isRoleChange: Boolean) {
        answer(
            PendingResponse(
                peerPubkey = recipientPubkey,
                peerRelay = recipientRelay,
                acceptedRole = null,
                isRoleChange = isRoleChange,
                recordedAt = System.currentTimeMillis()
            )
        )
    }

    fun retryPending() {
        scope.launch { retryPendingNow() }
    }

    private suspend fun retryPendingNow() {
        retryMutex.withLock {
            mutex.withLock { loadLocked() }.forEach { deliver(it) }
        }
    }

    /** Record the answer, then try to send it. */
    private suspend fun answer(response: PendingResponse) {
        mutex.withLock {
            save(loadLocked().filterNot { it.peerPubkey == response.peerPubkey } + response)
        }
        deliver(response)
    }

    private suspend fun deliver(response: PendingResponse) {
        val published = try {
            publish(response)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Keep the record: it is retried on the next start or reconnect.
            Log.w(TAG, "Failed to send track response", e)
            false
        }
        if (published) {
            mutex.withLock {
                save(loadLocked().filterNot {
                    it.peerPubkey == response.peerPubkey && it.recordedAt == response.recordedAt
                })
            }
        }
    }

    private suspend fun publish(response: PendingResponse) {
        val (privHex, pubHex) = keyManager.ensureKeypair()
        val acceptedRole = response.acceptedRole
        if (acceptedRole != null) {
            val payload = TrackAcceptPayload(
                acceptorPublicKeyHex = pubHex,
                acceptorDisplayName = displayNameOrFallback(),
                acceptorDeviceId = pubHex,
                acceptorRelayUrl = HARDCODED_RELAYS.first(),
                acceptedRole = acceptedRole
            )
            publish(
                response.peerPubkey, response.peerRelay, NostrEventKind.TRACK_ACCEPT,
                json.encodeToString(payload), privHex, pubHex
            )
            return
        }
        val payload = TrackDeclinePayload(
            declinerPublicKeyHex = pubHex,
            declinerDisplayName = displayNameOrFallback(),
            declinerDeviceId = pubHex,
            isRoleChange = response.isRoleChange
        )
        publish(
            response.peerPubkey, response.peerRelay, NostrEventKind.TRACK_DECLINE,
            json.encodeToString(payload), privHex, pubHex
        )
    }

    private suspend fun displayNameOrFallback(): String =
        prefs.settings.first().displayName.ifBlank { context.getString(R.string.notif_someone) }

    private fun publish(
        recipientPubkey: String,
        recipientRelay: String,
        kind: Int,
        plaintext: String,
        privHex: String,
        pubHex: String,
    ) {
        if (recipientRelay.isNotBlank()) relayClient.connect(recipientRelay)
        val encrypted = crypto.nip44Encrypt(crypto.hexToBytes(privHex), recipientPubkey, plaintext)
        relayClient.publishEvent(
            NostrEvent.build(
                privKeyHex = privHex,
                pubKeyHex = pubHex,
                kind = kind,
                content = encrypted,
                tags = listOf(listOf("p", recipientPubkey)),
                crypto = crypto
            )
        )
    }

    /**
     * Restore answers a previous process had not sent yet, dropping the ones past their expiry:
     * a requester who gave up on the answer days ago cannot use it, and retrying it on every
     * reconnect forever would keep publishing to a device that may be gone. A record without a
     * timestamp comes from an older build and is stamped now.
     */
    private suspend fun loadLocked(): List<PendingResponse> {
        val raw = prefs.getPendingTrackResponsesJson() ?: return emptyList()
        val stored = runCatching {
            json.decodeFromString<List<PendingResponse>>(raw)
        }.getOrDefault(emptyList())
        val now = System.currentTimeMillis()
        val kept = stored
            .map { if (it.recordedAt <= 0) it.copy(recordedAt = now) else it }
            .filter { now - it.recordedAt <= MAX_ANSWER_AGE_MS }
            .takeLast(MAX_PENDING)
        if (kept != stored) save(kept)
        return kept
    }

    private suspend fun save(pending: List<PendingResponse>) {
        prefs.setPendingTrackResponsesJson(
            if (pending.isEmpty()) null else json.encodeToString(pending)
        )
    }

    private companion object {
        /** An answer older than this belongs to a request the requester has given up on. */
        const val MAX_ANSWER_AGE_MS = 24 * 60 * 60_000L

        /** One per peer in practice; this only bounds a pathological backlog. */
        const val MAX_PENDING = 32
    }
}
