package com.locapeer.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.locapeer.data.dao.HeartbeatDao
import com.locapeer.data.dao.MessageDao
import com.locapeer.data.dao.PeerDao
import com.locapeer.data.dao.PeerSharingConfigDao
import com.locapeer.data.entity.HeartbeatEntity
import com.locapeer.data.entity.PeerEntity
import com.locapeer.data.entity.PeerSharingConfig
import com.locapeer.peer.PeerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject

data class ContactItem(
    val peer: PeerEntity,
    val lastHeartbeat: HeartbeatEntity?,
    val config: PeerSharingConfig?
)

@HiltViewModel
class ContactsViewModel @Inject constructor(
    private val peerDao: PeerDao,
    private val heartbeatDao: HeartbeatDao,
    private val sharingConfigDao: PeerSharingConfigDao,
    private val messageDao: MessageDao,
    private val peerManager: PeerManager
) : ViewModel() {

    val contacts = combine(
        peerDao.getAllPeers(),
        heartbeatDao.getLatestHeartbeatPerDevice(),
        sharingConfigDao.observeAll()
    ) { peers, heartbeats, configs ->
        val hbMap = heartbeats.associateBy { it.deviceId }
        val cfgMap = configs.associateBy { it.peerDeviceId }
        peers.map { peer -> ContactItem(peer, hbMap[peer.deviceId], cfgMap[peer.deviceId]) }
            .sortedWith(compareByDescending<ContactItem> { it.lastHeartbeat?.timestamp ?: 0L }
                .thenBy { it.peer.displayName })
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun removePeer(deviceId: String) {
        viewModelScope.launch { peerManager.removePeer(deviceId) }
    }

    fun renamePeer(peer: PeerEntity, newName: String) {
        viewModelScope.launch {
            peerDao.upsertPeer(peer.copy(displayName = newName.trim()))
        }
    }

    fun setLocationSharing(deviceId: String, enabled: Boolean) {
        viewModelScope.launch {
            val existing = sharingConfigDao.getForPeer(deviceId)
            if (existing != null) {
                sharingConfigDao.setSharingEnabled(deviceId, enabled)
            } else {
                sharingConfigDao.upsert(PeerSharingConfig(peerDeviceId = deviceId, sharingEnabled = enabled))
            }
        }
    }

    fun setMessaging(deviceId: String, enabled: Boolean) {
        viewModelScope.launch {
            val existing = sharingConfigDao.getForPeer(deviceId)
            if (existing != null) {
                sharingConfigDao.setMessagingEnabled(deviceId, enabled)
            } else {
                sharingConfigDao.upsert(PeerSharingConfig(peerDeviceId = deviceId, messagingEnabled = enabled))
            }
            if (enabled) messageDao.unblockMessagesFromPeer(deviceId)
        }
    }

    fun formatLastSeen(timestamp: Long): String {
        val now = System.currentTimeMillis()
        val diffMs = now - timestamp
        return when {
            diffMs < 60_000 -> "Just now"
            diffMs < 3_600_000 -> "${diffMs / 60_000}m ago"
            diffMs < 86_400_000 -> {
                val fmt = SimpleDateFormat("h:mm a", Locale.getDefault())
                fmt.format(Date(timestamp))
            }
            else -> {
                val cal = Calendar.getInstance().also { it.timeInMillis = timestamp }
                val today = Calendar.getInstance()
                val fmt = if (cal.get(Calendar.YEAR) == today.get(Calendar.YEAR))
                    SimpleDateFormat("d MMM, h:mm a", Locale.getDefault())
                else
                    SimpleDateFormat("d MMM yyyy", Locale.getDefault())
                fmt.format(Date(timestamp))
            }
        }
    }
}
