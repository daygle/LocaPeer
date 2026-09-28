package com.locapeer.invite

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.locapeer.data.dao.PendingRequestDao
import com.locapeer.data.entity.PendingRequestEntity
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
    private val pendingRequestDao: PendingRequestDao,
    private val actions: PendingRequestActions,
) : ViewModel() {

    private val _state = MutableStateFlow<IncomingRequestState>(IncomingRequestState.Idle)
    val state: StateFlow<IncomingRequestState> = _state

    // This screen is reachable through the exported MainActivity's "share-request" intent
    // extras, which any app on the device can forge. Every genuine request stores a
    // pending_requests row (in HeartbeatReceiver) before the screen can be shown, so both
    // actions below require that row and take the peer's relay URL and name from it - a
    // crafted intent can neither invent a request nor override a real one's relay.

    fun accept(senderPubkey: String, locationRole: String, messagingEnabled: Boolean) {
        respond(senderPubkey) { actions.accept(it, locationRole, messagingEnabled) }
    }

    fun decline(senderPubkey: String) {
        respond(senderPubkey) { actions.decline(it) }
    }

    private fun respond(senderPubkey: String, action: suspend (PendingRequestEntity) -> Unit) {
        viewModelScope.launch {
            _state.value = IncomingRequestState.Loading
            try {
                pendingRequestDao.getByPubkey(senderPubkey)?.let { action(it) }
            } finally {
                _state.value = IncomingRequestState.Done
            }
        }
    }
}
