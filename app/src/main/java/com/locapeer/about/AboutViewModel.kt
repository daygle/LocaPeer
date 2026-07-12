package com.locapeer.about

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.locapeer.data.dao.PendingMessageDao
import com.locapeer.nostr.NostrRelayClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class AboutViewModel @Inject constructor(
    val relayClient: NostrRelayClient,
    pendingMessageDao: PendingMessageDao
) : ViewModel() {

    /**
     * Live mirror of [NostrRelayClient.relayStatus] for the AboutScreen's diagnostics
     * card. Mirror-by-stateIn avoids the screen subscribing twice (once for the
     * old-shape `relayClient.relayStatus` direct and again for this VM-derived flow).
     */
    val relayStatus: StateFlow<Map<String, Boolean>> = relayClient.relayStatus
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /**
     * Count of messages queued in the relay outbox. A non-zero value on the About
     * screen turns into "N queued messages" so users see network backlog beyond the
     * simple connected/disconnected dot on the Relay Status Chip.
     */
    val pendingMessageCount: StateFlow<Int> = pendingMessageDao.countAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), 0)
}
