package com.locapeer.invite

import android.app.NotificationManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.locapeer.data.dao.PeerDao
import com.locapeer.data.dao.PendingRequestDao
import com.locapeer.data.entity.PeerEntity
import com.locapeer.data.entity.PendingRequestEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PendingRequestsViewModel @Inject constructor(
    private val pendingRequestDao: PendingRequestDao,
    private val peerDao: PeerDao,
    private val trackResponseSender: TrackResponseSender,
    private val notificationManager: NotificationManager
) : ViewModel() {

    val requests: StateFlow<List<PendingRequestEntity>> = pendingRequestDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun accept(request: PendingRequestEntity, locationRole: String, messagingEnabled: Boolean) {
        notificationManager.cancel(request.senderPubkey, com.locapeer.subscriber.NOTIF_ID_TRACK_REQUEST)
        viewModelScope.launch {
            val existing = peerDao.getPeer(request.senderPubkey)
            peerDao.upsertPeer(
                PeerEntity(
                    deviceId = request.senderPubkey,
                    displayName = existing?.displayName ?: request.senderName,
                    publicKeyHex = request.senderPubkey,
                    relayUrl = request.senderRelayUrl,
                    locationRole = locationRole,
                    messagingEnabled = messagingEnabled,
                    addedAt = existing?.addedAt ?: System.currentTimeMillis()
                )
            )
            trackResponseSender.sendAccept(request.senderPubkey, request.senderRelayUrl, locationRole)
            pendingRequestDao.deleteByPubkey(request.senderPubkey)
        }
    }

    fun decline(request: PendingRequestEntity) {
        notificationManager.cancel(request.senderPubkey, com.locapeer.subscriber.NOTIF_ID_TRACK_REQUEST)
        viewModelScope.launch {
            trackResponseSender.sendDecline(request.senderPubkey, request.senderRelayUrl, request.isRoleChange)
            pendingRequestDao.deleteByPubkey(request.senderPubkey)
        }
    }
}
