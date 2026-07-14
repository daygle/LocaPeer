package com.locapeer.peer

import android.content.Context
import android.util.Log
import com.locapeer.crypto.CryptoUtils
import com.locapeer.crypto.KeyManager
import com.locapeer.messaging.MediaCache
import dagger.hilt.android.qualifiers.ApplicationContext
import com.locapeer.data.dao.CircleDao
import com.locapeer.data.dao.GeofenceAssignmentDao
import com.locapeer.data.dao.HeartbeatDao
import com.locapeer.data.dao.MessageDao
import com.locapeer.data.dao.PeerDao
import com.locapeer.data.dao.PeerSharingConfigDao
import com.locapeer.data.dao.PendingRequestDao
import com.locapeer.data.dao.ProximityAlertDao
import com.locapeer.nostr.NostrEvent
import com.locapeer.nostr.NostrEventKind
import com.locapeer.nostr.NostrRelayClient
import com.locapeer.settings.AppPreferences
import kotlinx.coroutines.flow.first
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
    @ApplicationContext private val context: Context,
    private val peerDao: PeerDao,
    private val heartbeatDao: HeartbeatDao,
    private val messageDao: MessageDao,
    private val sharingConfigDao: PeerSharingConfigDao,
    private val circleDao: CircleDao,
    private val geofenceAssignmentDao: GeofenceAssignmentDao,
    private val proximityAlertDao: ProximityAlertDao,
    private val pendingRequestDao: PendingRequestDao,
    private val prefs: AppPreferences,
    private val keyManager: KeyManager,
    private val crypto: CryptoUtils,
    private val relayClient: NostrRelayClient
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * True when [deviceId] is the user's supervisor and supervised mode is active. The
     * user can't unilaterally remove their supervisor while supervised - that would end
     * supervision from a screen the settings gate doesn't cover. This is the hard
     * backstop behind the disabled UI; lifting supervised mode (gated in Settings behind
     * the supervisor's approval) is the only way to release it. A PEER_REMOVED *from*
     * the supervisor still cleans up normally (see handleRemovalByPeer), since the
     * supervisor is allowed to end the relationship.
     */
    private suspend fun isProtectedSupervisor(deviceId: String): Boolean {
        val settings = prefs.settings.first()
        return settings.supervisedModeEnabled &&
            settings.supervisorPubkey.equals(deviceId, ignoreCase = true)
    }

    /**
     * Delete every local trace of a contact. Centralised so all three removal paths
     * (we remove them, we remove ourselves from them, they remove us) wipe the same
     * set of per-peer rows - previously each path cleaned a different subset, leaving
     * message history, geofence assignments or proximity alerts behind to reactivate
     * if the contact was ever re-added.
     */
    private suspend fun wipeAllPeerData(deviceId: String) {
        peerDao.deletePeerById(deviceId)
        heartbeatDao.deleteAllForDevice(deviceId)
        messageDao.deleteAllForPeer(deviceId)
        sharingConfigDao.deleteForPeer(deviceId)
        // Drop them from every circle too, or future circle messages / circle location
        // shares would keep fanning out individually-encrypted copies to their pubkey.
        circleDao.removeMemberFromAllCircles(deviceId)
        // Per-contact tracking config keyed by device id: without these a re-added
        // contact silently reactivates geofences/proximity alerts the user set up before.
        geofenceAssignmentDao.deleteForTrackedDevice(deviceId)
        proximityAlertDao.deleteForPeer(deviceId)
        pendingRequestDao.deleteByPubkey(deviceId)
        // Drop any decrypted media we cached to view/play from this contact.
        MediaCache.clearDecryptedMedia(context)
    }

    /** Notify the peer, then remove them and all their data locally. Returns false
     *  (a no-op) when blocked because the peer is the active supervisor. */
    suspend fun removePeer(deviceId: String): Boolean {
        if (isProtectedSupervisor(deviceId)) {
            Log.w(TAG, "Refusing to remove supervisor $deviceId while supervised mode is active")
            return false
        }
        notifyPeerRemoved(deviceId)
        wipeAllPeerData(deviceId)
        return true
    }

    /**
     * Send DELETE_MY_MESSAGES + DELETE_MY_LOCATION to the peer and remove ourselves
     * from their contact list, then delete their data locally too.
     */
    suspend fun removeSelfFromPeer(deviceId: String): Boolean {
        if (isProtectedSupervisor(deviceId)) {
            Log.w(TAG, "Refusing to remove self from supervisor $deviceId while supervised mode is active")
            return false
        }
        sendDeleteMyMessages(deviceId)
        sendDeleteMyLocation(deviceId)
        notifyPeerRemoved(deviceId)
        // Also clean up locally - peer agreed to mutual tracking, so remove them here too
        wipeAllPeerData(deviceId)
        return true
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
        wipeAllPeerData(senderDeviceId)
        Log.i(TAG, "Removed peer $senderDeviceId after they removed us")
    }

    /** Called when we receive DELETE_MY_MESSAGES - wipe all messages sent by that pubkey. */
    suspend fun handleDeleteMyMessages(senderPubKeyHex: String) {
        messageDao.deleteAllFromSender(senderPubKeyHex)
        MediaCache.clearDecryptedMedia(context)
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
