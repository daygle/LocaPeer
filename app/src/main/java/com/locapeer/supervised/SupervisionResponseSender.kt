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
 * An answer is recorded BEFORE the supervisor changes any of its own state, and cleared only
 * once the relay accepted the event. For an accept, the record also tracks whether the sharing
 * configuration was applied, so a crash between recording and configuring is finished on the
 * next start instead of announcing a supervision the supervisor never wrote down.
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
        val configApplied: Boolean = false
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val scope = backgroundScope("SupervisionResponse")

    init {
        // Retry answers that never reached the relay. Constructed at app start (see
        // LocaPeerApplication), so this is the recovery hook.
        retryPending()
    }

    /** Accept: record first, then configure, then publish. */
    suspend fun accept(requesterPubkey: String, requesterRelay: String, requestCreatedAt: Long?) {
        val response = record(requesterPubkey, requesterRelay, true, requestCreatedAt, configApplied = false)
        try {
            applyAcceptConfiguration(requesterPubkey)
            markConfigurationApplied(response)
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
        scope.launch {
            val pending = mutex.withLock { load() }
            pending.forEach { response ->
                if (response.accepted && !response.configApplied) {
                    try {
                        applyAcceptConfiguration(response.requesterPubkey)
                        markConfigurationApplied(response)
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
        val response = PendingResponse(requesterPubkey, requesterRelay, accepted, requestCreatedAt, configApplied)
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

    private suspend fun load(): List<PendingResponse> {
        val raw = prefs.getPendingRegisterResponsesJson() ?: return emptyList()
        return runCatching {
            json.decodeFromString<List<PendingResponse>>(raw)
        }.getOrDefault(emptyList())
    }

    private suspend fun save(pending: List<PendingResponse>) {
        prefs.setPendingRegisterResponsesJson(
            if (pending.isEmpty()) null else json.encodeToString(pending)
        )
    }
}
