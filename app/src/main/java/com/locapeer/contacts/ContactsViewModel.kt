package com.locapeer.contacts

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.locapeer.data.dao.HeartbeatDao
import com.locapeer.data.dao.PeerDao
import com.locapeer.data.dao.PeerSharingConfigDao
import com.locapeer.data.dao.PendingRequestDao
import com.locapeer.data.entity.HeartbeatEntity
import com.locapeer.data.entity.PeerEntity
import com.locapeer.data.entity.PeerSharingConfig
import com.locapeer.peer.PeerManager
import com.locapeer.subscriber.PeerQuietPeriods
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ContactItem(
    val peer: PeerEntity,
    val lastHeartbeat: HeartbeatEntity?,
    val config: PeerSharingConfig?,
    /** How the row should describe the position we hold, including a deliberate pause. */
    val updateState: ContactUpdateState,
    /**
     * When [updateState] was evaluated. StateFlow drops equal values, so without a field
     * that changes on every tick the once-a-minute refresh would never reach the UI and
     * "last updated 4 minutes ago" would freeze at whatever it said on first composition.
     */
    val evaluatedAtMs: Long
)

/** String-resource id emitted on [ContactsViewModel.messages] when an action is blocked
 *  because the contact is the user's active supervisor. */
val MSG_SUPERVISOR_LOCKED = com.locapeer.R.string.contacts_supervisor_locked

@HiltViewModel
class ContactsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val peerDao: PeerDao,
    private val heartbeatDao: HeartbeatDao,
    private val sharingConfigDao: PeerSharingConfigDao,
    private val peerManager: PeerManager,
    private val pendingRequestDao: PendingRequestDao
) : ViewModel() {

    val pendingRequestCount = pendingRequestDao.observeCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    // One-shot user messages (e.g. a removal blocked because the contact is the active
    // supervisor). The screen collects this and shows a snackbar/toast.
    private val _messages = kotlinx.coroutines.flow.MutableSharedFlow<Int>(extraBufferCapacity = 4)
    val messages: kotlinx.coroutines.flow.SharedFlow<Int> = _messages

    /**
     * Re-evaluates the staleness labels once a minute while the list is on screen, so
     * "last updated" keeps ageing and a pause announced without a database write shows up.
     * WhileSubscribed below stops it as soon as the screen goes away.
     */
    private val minuteTicker: Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(60_000L)
        }
    }

    val contacts = combine(
        peerDao.getAllPeers().distinctUntilChanged(),
        heartbeatDao.getLatestHeartbeatPerDevice().distinctUntilChanged(),
        sharingConfigDao.observeAll().distinctUntilChanged(),
        minuteTicker
    ) { peers, heartbeats, configs, _ ->
        val hbMap = heartbeats.associateBy { it.deviceId }
        val cfgMap = configs.associateBy { it.peerDeviceId }
        // A contact's announced planned gap is recorded from their SHARING_PAUSED notice or
        // the quietUntilMs on their heartbeats; it is what distinguishes "paused" from
        // "this app stopped receiving positions".
        val now = System.currentTimeMillis()
        peers.map { peer ->
            val heartbeat = hbMap[peer.deviceId]
            ContactItem(
                peer = peer,
                lastHeartbeat = heartbeat,
                config = cfgMap[peer.deviceId],
                updateState = ContactStaleness.evaluate(
                    nowMs = now,
                    lastUpdateMs = heartbeat?.receivedAt,
                    quietUntilMs = PeerQuietPeriods.quietUntil(context, peer.deviceId),
                ),
                evaluatedAtMs = now,
            )
        }
            .sortedWith(compareByDescending<ContactItem> { it.lastHeartbeat?.timestamp ?: 0L }
                .thenBy { it.peer.displayName })
        // Stop re-querying on every incoming heartbeat once the screen is off-screen.
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun removePeer(deviceId: String) {
        viewModelScope.launch {
            if (!peerManager.removePeer(deviceId)) _messages.emit(MSG_SUPERVISOR_LOCKED)
        }
    }

    /** Send DELETE_MY_MESSAGES + DELETE_MY_LOCATION + PEER_REMOVED to the contact, then wipe locally. */
    fun removeSelfFromPeer(deviceId: String) {
        viewModelScope.launch {
            if (!peerManager.removeSelfFromPeer(deviceId)) _messages.emit(MSG_SUPERVISOR_LOCKED)
        }
    }

    fun purgeMyMessagesOnPeer(deviceId: String) {
        viewModelScope.launch { peerManager.sendDeleteMyMessages(deviceId) }
    }

    fun purgeMyLocationOnPeer(deviceId: String) {
        viewModelScope.launch { peerManager.sendDeleteMyLocation(deviceId) }
    }

    fun renamePeer(peer: PeerEntity, newName: String) {
        viewModelScope.launch {
            peerDao.rename(peer.deviceId, newName.trim())
        }
    }

    fun formatLastSeen(timestamp: Long): String = com.locapeer.util.DisplayFormat.relativeTimestamp(timestamp)
}
