package com.locapeer.peer

import android.util.Log
import com.locapeer.crypto.CryptoUtils
import com.locapeer.crypto.KeyManager
import com.locapeer.data.dao.HeartbeatDao
import com.locapeer.data.dao.PeerDao
import com.locapeer.data.dao.PeerSharingConfigDao
import com.locapeer.nostr.NostrEvent
import com.locapeer.nostr.NostrEventKind
import com.locapeer.nostr.NostrRelayClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "PeerManager"

@Serializable
data class PeerRemovedPayload(val removedByDeviceId: String)

@Singleton
class PeerManager @Inject constructor(
    private val peerDao: PeerDao,
    private val heartbeatDao: HeartbeatDao,
    private val sharingConfigDao: PeerSharingConfigDao,
    private val keyManager: KeyManager,
    private val crypto: CryptoUtils,
    private val relayClient: NostrRelayClient
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Notify the peer, then remove them and all their data locally. */
    suspend fun removePeer(deviceId: String) {
        notifyPeerRemoved(deviceId)
        peerDao.deletePeerById(deviceId)
        heartbeatDao.deleteAllForDevice(deviceId)
        sharingConfigDao.deleteForPeer(deviceId)
    }

    /** Called when we receive a PEER_REMOVED event — remove the sender silently. */
    suspend fun handleRemovalByPeer(senderDeviceId: String) {
        peerDao.deletePeerById(senderDeviceId)
        heartbeatDao.deleteAllForDevice(senderDeviceId)
        sharingConfigDao.deleteForPeer(senderDeviceId)
        Log.i(TAG, "Removed peer $senderDeviceId after they removed us")
    }

    private suspend fun notifyPeerRemoved(deviceId: String) {
        try {
            val peer = peerDao.getPeer(deviceId) ?: return
            val (privHex, pubHex) = keyManager.ensureKeypair()
            val payload = PeerRemovedPayload(removedByDeviceId = pubHex)
            val encrypted = crypto.nip44Encrypt(
                crypto.hexToBytes(privHex),
                peer.publicKeyHex,
                json.encodeToString(payload)
            )
            val event = NostrEvent.build(
                privKeyHex = privHex,
                pubKeyHex = pubHex,
                kind = NostrEventKind.PEER_REMOVED,
                content = encrypted,
                tags = listOf(listOf("p", peer.publicKeyHex)),
                crypto = crypto
            )
            relayClient.publishEvent(event)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send peer removal notification", e)
            // Still proceed with local deletion even if notification fails
        }
    }
}
