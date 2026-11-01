package com.locapeer.invite

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.locapeer.data.dao.PendingRequestDao
import com.locapeer.data.entity.PendingRequestEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PendingRequestsViewModel @Inject constructor(
    pendingRequestDao: PendingRequestDao,
    private val actions: PendingRequestActions,
    contactAddApprovalManager: com.locapeer.supervised.ContactAddApprovalManager,
) : ViewModel() {

    val requests: StateFlow<List<PendingRequestEntity>> = pendingRequestDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Pubkeys whose supervised contact-add is awaiting the supervisor's decision
     *  (kind 1067): those rows show a waiting badge instead of fresh accept affordances. */
    val awaitingApprovalKeys: StateFlow<Set<String>> = contactAddApprovalManager.pending
        .map { queue -> queue.map { it.contactPubkeyHex }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    fun accept(request: PendingRequestEntity, locationRole: String, messagingEnabled: Boolean) {
        viewModelScope.launch { actions.accept(request, locationRole, messagingEnabled) }
    }

    fun decline(request: PendingRequestEntity) {
        viewModelScope.launch { actions.decline(request) }
    }
}
