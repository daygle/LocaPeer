package com.locapeer.invite

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.locapeer.crypto.KeyManager
import com.locapeer.settings.AppPreferences
import com.locapeer.settings.HARDCODED_RELAYS
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Base64
import javax.inject.Inject

data class InviteUiState(
    val publicKeyHex: String = "",
    val qrBitmap: Bitmap? = null,
    val inviteLink: String = "",
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
                    relayUrl = HARDCODED_RELAYS.first(),
                    deviceId = pubHex
                )
                val json = Json.encodeToString(inviteData)
                val base64 = Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())
                val inviteLink = "locapeer://invite?data=$base64"

                val bitmap = qrGenerator.generate(json)
                if (bitmap != null) {
                    _state.value = InviteUiState(
                        publicKeyHex = pubHex,
                        qrBitmap = bitmap,
                        inviteLink = inviteLink
                    )
                } else {
                    _state.value = InviteUiState(publicKeyHex = pubHex, error = true)
                }
            } catch (e: Exception) {
                _state.value = InviteUiState(error = true)
            }
        }
    }
}
