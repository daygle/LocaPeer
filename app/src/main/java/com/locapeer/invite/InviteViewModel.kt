package com.locapeer.invite

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.locapeer.crypto.KeyManager
import com.locapeer.settings.AppPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject

data class InviteUiState(
    val publicKeyHex: String = "",
    val qrBitmap: Bitmap? = null,
    val error: Boolean = false
)

@HiltViewModel
class InviteViewModel @Inject constructor(
    private val keyManager: KeyManager,
    private val prefs: AppPreferences,
    private val qrGenerator: QrCodeGenerator
) : ViewModel() {

    private val _state = MutableStateFlow(InviteUiState())
    val state: StateFlow<InviteUiState> = _state

    init {
        viewModelScope.launch {
            try {
                val (_, pubHex) = keyManager.ensureKeypair()
                val settings = prefs.settings.first()
                val inviteData = InviteData(
                    publicKeyHex = pubHex,
                    displayName = settings.displayName,
                    relayUrl = "wss://relay.daygle.net",
                    deviceId = pubHex
                )
                val json = Json.encodeToString(inviteData)
                val bitmap = qrGenerator.generate(json)
                if (bitmap != null) {
                    _state.value = InviteUiState(publicKeyHex = pubHex, qrBitmap = bitmap)
                } else {
                    _state.value = InviteUiState(publicKeyHex = pubHex, error = true)
                }
            } catch (e: Exception) {
                _state.value = InviteUiState(error = true)
            }
        }
    }
}
