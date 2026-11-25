package com.locapeer.sharing

import android.util.Log
import com.locapeer.crypto.CryptoUtils
import com.locapeer.crypto.KeyManager
import com.locapeer.data.dao.PeerDao
import com.locapeer.data.entity.PeerEntity
import com.locapeer.nostr.NostrEvent
import com.locapeer.nostr.NostrEventKind
import com.locapeer.nostr.NostrRelayClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Kind 1072 payload: the sender stopped sending its location to the recipient on purpose, or
 * resumed. [quietUntilMs] is 0 for "resumed", [SharingSchedule.QUIET_INDEFINITELY] for a stop
 * with no planned end, or the epoch ms sending is expected back.
 */
@Serializable
data class SharingPausePayload(
    val deviceId: String,
    val quietUntilMs: Long,
)

/**
 * Tells contacts that location updates were stopped deliberately (a per-contact pause, or
 * location sharing switched off) and when they resume, so their missed-update alerts don't
 * fire for a planned gap. Best effort: a notice that never arrives only means an alert that
 * would have fired anyway.
 */
@Singleton
class SharingPauseNotifier @Inject constructor(
    private val peerDao: PeerDao,
    private val keyManager: KeyManager,
    private val crypto: CryptoUtils,
    private val relayClient: NostrRelayClient,
) {
    private val json = Json { encodeDefaults = true }

    /** Sends the notice to each contact in [deviceIds] we share our location with. */
    suspend fun notify(deviceIds: Collection<String>, quietUntilMs: Long) {
        if (deviceIds.isEmpty()) return
        try {
            val (privHex, pubHex) = keyManager.ensureKeypair()
            val privBytes = crypto.hexToBytes(privHex)
            val content = json.encodeToString(SharingPausePayload(pubHex, quietUntilMs))
            for (deviceId in deviceIds) {
                val peer = peerDao.getPeer(deviceId) ?: continue
                if (peer.locationRole != PeerEntity.ROLE_SEND && peer.locationRole != PeerEntity.ROLE_SEND_RECEIVE) continue
                val event = NostrEvent.build(
                    privKeyHex = privHex,
                    pubKeyHex = pubHex,
                    kind = NostrEventKind.SHARING_PAUSED,
                    content = crypto.nip44Encrypt(privBytes, peer.publicKeyHex, content),
                    tags = listOf(listOf("p", peer.publicKeyHex)),
                    crypto = crypto
                )
                relayClient.publishEvent(event)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("SharingPauseNotifier", "Failed to send sharing-paused notice", e)
        }
    }
}
