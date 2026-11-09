package com.locapeer.supervised

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.locapeer.settings.AppPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SupervisionGateViewModel @Inject constructor(
    prefs: AppPreferences,
    private val manager: SupervisedModeManager
) : ViewModel() {

    /** Whether supervised-mode gates apply: requested AND accepted by the supervisor. */
    val supervisedModeEnabled: StateFlow<Boolean> = prefs.settings
        .map { it.supervisionActive }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val unlockState = manager.unlockState

    init {
        // Finish a release whose approval was persisted before the flags were cleared, i.e.
        // a process death in that window. Reaching the gate means supervision still reads as
        // active, so this is the reliable place to apply the marker.
        viewModelScope.launch { prefs.applyPendingSupervisionRelease() }
    }

    fun requestAccess() = manager.requestAccess()
    fun reset() = manager.reset()
}
