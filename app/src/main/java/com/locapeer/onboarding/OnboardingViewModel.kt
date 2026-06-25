package com.locapeer.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.locapeer.crypto.KeyManager
import com.locapeer.settings.AppPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class OnboardingState(
    val displayName: String = "",
    val publicKeyHex: String = "",
    val isLoading: Boolean = true
)

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val keyManager: KeyManager,
    private val prefs: AppPreferences
) : ViewModel() {

    private val _state = MutableStateFlow(OnboardingState())
    val state: StateFlow<OnboardingState> = _state

    init {
        viewModelScope.launch {
            val (_, pubHex) = keyManager.ensureKeypair()
            _state.value = OnboardingState(publicKeyHex = pubHex, isLoading = false)
        }
    }

    fun setDisplayName(name: String) {
        _state.value = _state.value.copy(displayName = name)
    }

    fun complete(onDone: () -> Unit) {
        val name = _state.value.displayName.trim()
        if (name.isBlank()) return
        viewModelScope.launch {
            prefs.updateDisplayName(name)
            prefs.setOnboardingComplete(true)
            onDone()
        }
    }
}
