package com.locapeer.peer

import android.util.Log
import com.locapeer.crypto.CryptoUtils
import com.locapeer.crypto.KeyManager
import com.locapeer.data.dao.HeartbeatDao
import com.locapeer.data.dao.MessageDao
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

@Serializable
data class DataDeletionPayload(val senderPubKeyHex: String)

@Singleton
class PeerManager @Inject constructor(
    private val peerDao: PeerDao,
    private val heartbeatDao: HeartbeatDao,
    private val messageDao: MessageDao,
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

    /**
     * Send DELETE_MY_MESSAGES + DELETE_MY_LOCATION to the peer and remove ourselves
     * from their contact list, then delete their data locally too.
     */
    suspend fun removeSelfFromPeer(deviceId: String) {
        sendDeleteMyMessages(deviceId)
        sendDeleteMyLocation(deviceId)
        notifyPeerRemoved(deviceId)
        // Also clean up locally - peer agreed to mutual tracking, so remove them here too
        peerDao.deletePeerById(deviceId)
        heartbeatDao.deleteAllForDevice(deviceId)
        messageDao.deleteAllForPeer(deviceId)
        sharingConfigDao.deleteForPeer(deviceId)
    }

    /** Purge all messages we sent to a specific peer on their device. */
    suspend fun sendDeleteMyMessages(deviceId: String) {
        sendDataEvent(deviceId, NostrEventKind.DELETE_MY_MESSAGES)
    }

    /** Purge all location data we sent to a specific peer on their device. */
    suspend fun sendDeleteMyLocation(deviceId: String) {
        sendDataEvent(deviceId, NostrEventKind.DELETE_MY_LOCATION)
    }

    /** Called when we receive a PEER_REMOVED event - remove the sender silently. */
    suspend fun handleRemovalByPeer(senderDeviceId: String) {
        peerDao.deletePeerById(senderDeviceId)
        heartbeatDao.deleteAllForDevice(senderDeviceId)
        sharingConfigDao.deleteForPeer(senderDeviceId)
        Log.i(TAG, "Removed peer $senderDeviceId after they removed us")
    }

    /** Called when we receive DELETE_MY_MESSAGES - wipe all messages sent by that pubkey. */
    suspend fun handleDeleteMyMessages(senderPubKeyHex: String) {
        messageDao.deleteAllFromSender(senderPubKeyHex)
        Log.i(TAG, "Deleted all messages from $senderPubKeyHex at their request")
    }

    /** Called when we receive DELETE_MY_LOCATION - wipe all heartbeats from that device. */
    suspend fun handleDeleteMyLocation(senderDeviceId: String) {
        heartbeatDao.deleteAllForDevice(senderDeviceId)
        Log.i(TAG, "Deleted all location data from $senderDeviceId at their request")
    }

    private suspend fun sendDataEvent(deviceId: String, kind: Int) {
        try {
            val peer = peerDao.getPeer(deviceId) ?: return
            val (privHex, pubHex) = keyManager.ensureKeypair()
            val payload = DataDeletionPayload(senderPubKeyHex = pubHex)
            val encrypted = crypto.nip44Encrypt(
                crypto.hexToBytes(privHex),
                peer.publicKeyHex,
                json.encodeToString(payload)
            )
            val event = NostrEvent.build(
                privKeyHex = privHex,
                pubKeyHex = pubHex,
                kind = kind,
                content = encrypted,
                tags = listOf(listOf("p", peer.publicKeyHex)),
                crypto = crypto
            )
            relayClient.publishEvent(event)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send data deletion event (kind=$kind)", e)
        }
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
        }
    }
}
