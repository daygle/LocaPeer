package com.locapeer.supervised

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.locapeer.settings.AppPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class SupervisionGateViewModel @Inject constructor(
    prefs: AppPreferences,
    private val manager: SupervisedModeManager
) : ViewModel() {

    val supervisedModeEnabled: StateFlow<Boolean> = prefs.settings
        .map { it.supervisedModeEnabled }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val unlockState = manager.unlockState

    fun requestAccess() = manager.requestAccess()
    fun reset() = manager.reset()
}
