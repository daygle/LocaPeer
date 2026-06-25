package com.locapeer.invite

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.locapeer.crypto.KeyManager
import com.locapeer.data.dao.PeerDao
import com.locapeer.data.entity.PeerEntity
import com.locapeer.nostr.NostrRelayClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import javax.inject.Inject

data class ScanState(
    val success: Boolean = false,
    val addedName: String = "",
    val error: String? = null
)

@HiltViewModel
class ScanViewModel @Inject constructor(
    private val peerDao: PeerDao,
    private val keyManager: KeyManager,
    private val relayClient: NostrRelayClient
) : ViewModel() {

    private val _scanState = MutableStateFlow(ScanState())
    val scanState: StateFlow<ScanState> = _scanState

    private var processed = false

    fun processQrCode(raw: String) {
        if (processed) return
        processed = true
        viewModelScope.launch {
            try {
                val invite = Json { ignoreUnknownKeys = true }.decodeFromString<InviteData>(raw)
                val peer = PeerEntity(
                    deviceId = invite.deviceId,
                    displayName = invite.displayName,
                    publicKeyHex = invite.publicKeyHex,
                    relayUrl = invite.relayUrl,
                    role = "BROADCASTER"
                )
                peerDao.upsertPeer(peer)
                relayClient.connect(invite.relayUrl)
                _scanState.value = ScanState(success = true, addedName = invite.displayName)
            } catch (e: Exception) {
                _scanState.value = ScanState(error = e.message ?: "Unknown error")
                processed = false
            }
        }
    }

    fun reset() {
        processed = false
        _scanState.value = ScanState()
    }
}
