package com.locapeer.supervised

import android.util.Log
import com.locapeer.crypto.CryptoUtils
import com.locapeer.crypto.KeyManager
import com.locapeer.data.dao.PeerDao
import com.locapeer.data.dao.PeerSharingConfigDao
import com.locapeer.data.entity.PeerEntity
import com.locapeer.data.entity.PeerSharingConfig
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

private const val TAG = "SupervisionResponse"

/**
 * Sends the supervisor's answers to supervised devices (register accept/decline) with a
 * durable record, so an answer whose publish failed - or was interrupted by a process death -
 * is sent again on the next start instead of leaving the supervisor supervising a device that
 * never learned it was accepted.
 *
 * An answer is recorded BEFORE the supervisor changes any of its own state, and cleared once
 * it has been handed to the relay client, whose persistent outbox keeps it until a relay takes
 * it (no relay acknowledgement is awaited here). For an accept, the record also tracks whether the sharing
 * configuration was applied, so a crash between recording and configuring is finished on the
 * next start instead of announcing a supervision the supervisor never wrote down.
 *
 * A record expires after [MAX_ANSWER_AGE_MS]: an answer the requesting device has long stopped
 * waiting for cannot become useful again, and retrying it on every reconnect forever would
 * keep publishing to a device that may no longer exist.
 */
@Singleton
class SupervisionResponseSender @Inject constructor(
    private val prefs: AppPreferences,
    private val keyManager: KeyManager,
    private val crypto: CryptoUtils,
    private val relayClient: NostrRelayClient,
    private val sharingConfigDao: PeerSharingConfigDao,
    private val peerDao: PeerDao,
) {
    @Serializable
    data class PendingResponse(
        val requesterPubkey: String,
        val requesterRelay: String,
        val accepted: Boolean,
        /** The echoed request time; null when the requesting build is older. */
        val requestCreatedAt: Long? = null,
        /** Accept only: the supervisor's own flags/role for this device are already written. */
        val configApplied: Boolean = false,
        /** When the answer was recorded; 0 for a record written by an older build. */
        val recordedAt: Long = 0
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    /** Serialises retry passes: app start and a reconnect can fire at the same time. */
    private val retryMutex = Mutex()
    private val scope = backgroundScope("SupervisionResponse")

    init {
        // Retry every time a relay becomes reachable, not only at process start: an answer
        // recorded during an outage must go out as soon as the connection returns.
        scope.launch {
            relayClient.relayStatus
                .map { status -> status.values.any { connected -> connected } }
                .distinctUntilChanged()
                .filter { connected -> connected }
                .collect { retryPendingNow() }
        }
    }

    /** Accept: record first, then configure, then publish. */
    suspend fun accept(requesterPubkey: String, requesterRelay: String, requestCreatedAt: Long?) {
        val response = record(requesterPubkey, requesterRelay, true, requestCreatedAt, configApplied = false)
        try {
            applyAcceptConfiguration(requesterPubkey)
            markConfigurationApplied(response)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Keep the unconfigured record: the next start finishes the configuration and
            // only then tells the device it is supervised.
            Log.w(TAG, "Failed to apply supervised-mode configuration", e)
            return
        }
        deliver(response)
    }

    /** Decline: nothing local to apply, so the answer is recorded already complete. */
    suspend fun decline(requesterPubkey: String, requesterRelay: String, requestCreatedAt: Long?) {
        deliver(record(requesterPubkey, requesterRelay, false, requestCreatedAt, configApplied = true))
    }

    fun retryPending() {
        scope.launch { retryPendingNow() }
    }

    private suspend fun retryPendingNow() {
        retryMutex.withLock {
            mutex.withLock { load() }.forEach { response ->
                if (response.accepted && !response.configApplied) {
                    try {
                        applyAcceptConfiguration(response.requesterPubkey)
                        markConfigurationApplied(response)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to finish supervision configuration", e)
                        return@forEach
                    }
                }
                deliver(response)
            }
        }
    }

    private suspend fun record(
        requesterPubkey: String,
        requesterRelay: String,
        accepted: Boolean,
        requestCreatedAt: Long?,
        configApplied: Boolean,
    ): PendingResponse {
        val response = PendingResponse(
            requesterPubkey,
            requesterRelay,
            accepted,
            requestCreatedAt,
            configApplied,
            recordedAt = System.currentTimeMillis(),
        )
        mutex.withLock {
            // Same requester + request time is one answer: a retried request or a second
            // tap must not queue two.
            save(load().filterNot { key(it) == key(response) } + response)
        }
        return response
    }

    private suspend fun markConfigurationApplied(response: PendingResponse) {
        mutex.withLock {
            save(
                load().map {
                    if (key(it) == key(response)) it.copy(configApplied = true) else it
                }
            )
        }
    }

    /**
     * Supervision is only meaningful if the supervisor records it too, and only works if the
     * supervisor's peer receives the device's heartbeats - hence the role promotion.
     */
    private suspend fun applyAcceptConfiguration(requesterPubkey: String) {
        val cfg = sharingConfigDao.getForPeer(requesterPubkey)
        if (cfg != null) {
            sharingConfigDao.setIsMySupervised(requesterPubkey, true)
            sharingConfigDao.setNotifyOnMissedHeartbeat(requesterPubkey, true)
        } else {
            sharingConfigDao.upsert(
                PeerSharingConfig(
                    peerDeviceId = requesterPubkey,
                    isMySupervised = true,
                    notifyOnMissedHeartbeat = true
                )
            )
        }
        // Supervising a device implies receiving its location (NONE -> RECEIVE,
        // SEND -> SEND_RECEIVE), leaving any existing SEND capability intact.
        val peer = peerDao.getPeer(requesterPubkey)
        if (peer != null) {
            val promotedRole = PeerEntity.roleWithReceive(peer.locationRole)
            if (promotedRole != peer.locationRole) {
                peerDao.upsertPeer(peer.copy(locationRole = promotedRole))
            }
        }
    }

    private suspend fun deliver(response: PendingResponse) {
        val published = try {
            publish(response)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Keep the record: the next start sends it again.
            Log.w(TAG, "Failed to send supervision response", e)
            false
        }
        if (published) {
            mutex.withLock { save(load().filterNot { key(it) == key(response) }) }
        }
    }

    private suspend fun publish(response: PendingResponse) {
        val (privHex, pubHex) = keyManager.ensureKeypair()
        val payload = json.encodeToString(
            SupervisedRegisterResponsePayload(
                devicePubkeyHex = response.requesterPubkey,
                accepted = response.accepted,
                requestCreatedAt = response.requestCreatedAt
            )
        )
        val encrypted = crypto.nip44Encrypt(
            crypto.hexToBytes(privHex),
            response.requesterPubkey,
            payload
        )
        if (response.requesterRelay.isNotBlank()) relayClient.connect(response.requesterRelay)
        relayClient.publishEvent(
            NostrEvent.build(
                privKeyHex = privHex,
                pubKeyHex = pubHex,
                kind = if (response.accepted) NostrEventKind.SUPERVISED_REGISTER_ACCEPT
                       else NostrEventKind.SUPERVISED_REGISTER_DECLINE,
                content = encrypted,
                tags = listOf(listOf("p", response.requesterPubkey)),
                crypto = crypto
            )
        )
    }

    private fun key(response: PendingResponse) =
        "${response.requesterPubkey}:${response.requestCreatedAt ?: 0L}"

    /**
     * Restore answers a previous process had not sent yet, dropping the ones past their expiry.
     * Pruning happens here so it applies to every pass (start, reconnect, a new answer) rather
     * than only at construction, and the shortened list is written back so an expired record
     * cannot be read again. A record without a timestamp comes from an older build and is
     * stamped now: it is owed an answer, just not forever.
     */
    private suspend fun load(): List<PendingResponse> {
        val raw = prefs.getPendingRegisterResponsesJson() ?: return emptyList()
        val stored = runCatching {
            json.decodeFromString<List<PendingResponse>>(raw)
        }.getOrDefault(emptyList())
        val now = System.currentTimeMillis()
        val kept = stored
            .map { if (it.recordedAt <= 0) it.copy(recordedAt = now) else it }
            .filter { now - it.recordedAt <= MAX_ANSWER_AGE_MS }
        if (kept != stored) save(kept)
        return kept
    }

    private suspend fun save(pending: List<PendingResponse>) {
        prefs.setPendingRegisterResponsesJson(
            if (pending.isEmpty()) null else json.encodeToString(pending)
        )
    }

    private companion object {
        /** Answers older than this belong to requests the device has given up on. */
        const val MAX_ANSWER_AGE_MS = 24 * 60 * 60_000L
    }
}
