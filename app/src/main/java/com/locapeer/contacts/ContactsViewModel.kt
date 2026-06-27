package com.locapeer.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.locapeer.data.dao.HeartbeatDao
import com.locapeer.data.dao.PeerDao
import com.locapeer.data.entity.HeartbeatEntity
import com.locapeer.data.entity.PeerEntity
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
    val lastHeartbeat: HeartbeatEntity?
)

@HiltViewModel
class ContactsViewModel @Inject constructor(
    private val peerDao: PeerDao,
    private val heartbeatDao: HeartbeatDao
) : ViewModel() {

    val contacts = combine(
        peerDao.getAllPeers(),
        heartbeatDao.getLatestHeartbeatPerDevice()
    ) { peers, heartbeats ->
        val hbMap = heartbeats.associateBy { it.deviceId }
        peers.map { peer -> ContactItem(peer, hbMap[peer.deviceId]) }
            .sortedWith(compareByDescending<ContactItem> { it.lastHeartbeat?.timestamp ?: 0L }
                .thenBy { it.peer.displayName })
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun removePeer(deviceId: String) {
        viewModelScope.launch {
            peerDao.deletePeerById(deviceId)
            heartbeatDao.deleteAllForDevice(deviceId)
        }
    }

    fun renamePeer(peer: PeerEntity, newName: String) {
        viewModelScope.launch {
            peerDao.upsertPeer(peer.copy(displayName = newName.trim()))
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
