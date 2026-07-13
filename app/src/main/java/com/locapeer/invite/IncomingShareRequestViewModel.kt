package com.locapeer.invite

import android.app.NotificationManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.locapeer.data.dao.PeerDao
import com.locapeer.data.dao.PendingRequestDao
import com.locapeer.data.entity.PeerEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class IncomingRequestState {
    object Idle : IncomingRequestState()
    object Loading : IncomingRequestState()
    object Done : IncomingRequestState()
}

@HiltViewModel
class IncomingShareRequestViewModel @Inject constructor(
    private val peerDao: PeerDao,
    private val pendingRequestDao: PendingRequestDao,
    private val trackResponseSender: TrackResponseSender,
    private val notificationManager: NotificationManager
) : ViewModel() {

    private val _state = MutableStateFlow<IncomingRequestState>(IncomingRequestState.Idle)
    val state: StateFlow<IncomingRequestState> = _state

    // This screen is reachable through the exported MainActivity's "share-request" intent
    // extras, which any app on the device can forge. Every genuine request stores a
    // pending_requests row (in HeartbeatReceiver) before the screen can be shown, so both
    // actions below require that row and take the peer's relay URL and name from it - a
    // crafted intent can neither invent a request nor override a real one's relay.

    fun accept(senderPubkey: String, senderName: String, senderRelay: String, locationRole: String, messagingEnabled: Boolean) {
        notificationManager.cancel(senderPubkey, com.locapeer.subscriber.NOTIF_ID_TRACK_REQUEST)
        viewModelScope.launch {
            _state.value = IncomingRequestState.Loading
            val request = pendingRequestDao.getByPubkey(senderPubkey) ?: run {
                _state.value = IncomingRequestState.Done
                return@launch
            }
            val existing = peerDao.getPeer(senderPubkey)
            peerDao.upsertPeer(
                PeerEntity(
                    deviceId = senderPubkey,
                    displayName = existing?.displayName ?: request.senderName,
                    publicKeyHex = senderPubkey,
                    relayUrl = request.senderRelayUrl,
                    locationRole = locationRole,
                    messagingEnabled = messagingEnabled,
                    addedAt = existing?.addedAt ?: System.currentTimeMillis()
                )
            )
            trackResponseSender.sendAccept(senderPubkey, request.senderRelayUrl, locationRole)
            pendingRequestDao.deleteByPubkey(senderPubkey)
            _state.value = IncomingRequestState.Done
        }
    }

    fun decline(senderPubkey: String, senderRelay: String, isRoleChange: Boolean = false) {
        notificationManager.cancel(senderPubkey, com.locapeer.subscriber.NOTIF_ID_TRACK_REQUEST)
        viewModelScope.launch {
            _state.value = IncomingRequestState.Loading
            val request = pendingRequestDao.getByPubkey(senderPubkey)
            if (request != null) {
                trackResponseSender.sendDecline(senderPubkey, request.senderRelayUrl, request.isRoleChange)
                pendingRequestDao.deleteByPubkey(senderPubkey)
            }
            _state.value = IncomingRequestState.Done
        }
    }
}
