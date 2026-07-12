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

enum class OnboardingStep {
    IDENTITY,
    BACKUP,
    PERMISSIONS,
    BACKGROUND_LOCATION,
    BATTERY,
    DONE
}

data class OnboardingState(
    val displayName: String = "",
    val publicKeyHex: String = "",
    val step: OnboardingStep = OnboardingStep.IDENTITY,
    val isLoading: Boolean = true,
    val importError: String? = null,
    val showPermissionDeniedError: Boolean = false,
    /** Populated on demand by [revealPrivateKey] so the backup step can show/copy the key. */
    val privateKeyHex: String = "",
    /** True once the user restored an existing key; they already have a backup, so skip BACKUP. */
    val restoredFromBackup: Boolean = false
)

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val keyManager: KeyManager,
    private val prefs: AppPreferences,
    private val crypto: com.locapeer.crypto.CryptoUtils
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
        _state.value = _state.value.copy(displayName = name, showPermissionDeniedError = false)
    }

    fun nextStep() {
        val current = _state.value.step
        val next = when (current) {
            // A restored user already holds their key, so skip the "back up your identity" step.
            OnboardingStep.IDENTITY ->
                if (_state.value.restoredFromBackup) OnboardingStep.PERMISSIONS else OnboardingStep.BACKUP
            OnboardingStep.BACKUP -> OnboardingStep.PERMISSIONS
            OnboardingStep.PERMISSIONS -> OnboardingStep.BACKGROUND_LOCATION
            OnboardingStep.BACKGROUND_LOCATION -> OnboardingStep.BATTERY
            OnboardingStep.BATTERY -> OnboardingStep.DONE
            OnboardingStep.DONE -> OnboardingStep.DONE
        }
        _state.value = _state.value.copy(step = next, showPermissionDeniedError = false)
    }

    fun setPermissionDenied(denied: Boolean) {
        _state.value = _state.value.copy(showPermissionDeniedError = denied)
    }

    fun importPrivateKey(privHex: String) {
        val cleaned = privHex.trim().lowercase()
        if (!cleaned.matches(Regex("^[0-9a-f]{64}$"))) {
            _state.value = _state.value.copy(importError = context.getString(com.locapeer.R.string.onboarding_error_key_length))
            return
        }

        try {
            if (!crypto.isValidPrivateKey(crypto.hexToBytes(cleaned))) {
                _state.value = _state.value.copy(importError = context.getString(com.locapeer.R.string.onboarding_error_key_range))
                return
            }
        } catch (_: Exception) {
            _state.value = _state.value.copy(importError = context.getString(com.locapeer.R.string.onboarding_error_hex))
            return
        }

        _state.value = _state.value.copy(isLoading = true, importError = null)
        viewModelScope.launch {
            try {
                keyManager.importPrivateKey(cleaned)
                val pubHex = keyManager.getPublicKeyHex() ?: ""
                _state.value = _state.value.copy(publicKeyHex = pubHex, isLoading = false, restoredFromBackup = true)
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isLoading = false,
                    importError = context.getString(com.locapeer.R.string.onboarding_error_import_failed, e.message ?: "")
                )
            }
        }
    }

    fun clearImportError() {
        _state.value = _state.value.copy(importError = null)
    }

    /** Fetches the private key on demand so the backup step can display and copy it. */
    fun revealPrivateKey() {
        if (_state.value.privateKeyHex.isNotEmpty()) return
        viewModelScope.launch {
            try {
                _state.value = _state.value.copy(privateKeyHex = keyManager.exportPrivateKeyHex())
            } catch (_: Exception) {
                // Leave it hidden; the user can still back up later from Settings.
            }
        }
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
