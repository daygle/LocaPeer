package com.locapeer.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.locapeer.data.dao.PendingMessageDao
import com.locapeer.data.entity.PendingMessageEntity
import com.locapeer.nostr.NostrRelayClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Backs both the Connection ("Network") settings screen and the Queued Messages
 * drill-in screen. [pendingCount] drives the summary row in Connection settings;
 * [messages] lists every event waiting in the relay outbox for the drill-in view.
 */
@HiltViewModel
class QueuedMessagesViewModel @Inject constructor(
    pendingMessageDao: PendingMessageDao,
    private val relayClient: NostrRelayClient,
) : ViewModel() {

    val pendingCount: StateFlow<Int> = pendingMessageDao.countAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), 0)

    val messages: StateFlow<List<PendingMessageEntity>> = pendingMessageDao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    /** Retry delivery of every queued message immediately; the list live-updates as sends succeed. */
    fun flushNow() {
        relayClient.flushAllPending()
    }
}
