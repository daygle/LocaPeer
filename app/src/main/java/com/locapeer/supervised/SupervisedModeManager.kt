package com.locapeer.supervised

import com.locapeer.crypto.CryptoUtils
import com.locapeer.crypto.KeyManager
import com.locapeer.nostr.NostrEvent
import com.locapeer.nostr.NostrEventKind
import com.locapeer.nostr.NostrRelayClient
import com.locapeer.settings.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SupervisedModeManager @Inject constructor(
    private val keyManager: KeyManager,
    private val crypto: CryptoUtils,
    private val relayClient: NostrRelayClient,
    private val prefs: AppPreferences
) {
    sealed class UnlockState {
        object Idle : UnlockState()
        data class Requesting(val requestId: String) : UnlockState()
        object Approved : UnlockState()
        object Denied : UnlockState()
        object TimedOut : UnlockState()
    }

    private val _unlockState = MutableStateFlow<UnlockState>(UnlockState.Idle)
    val unlockState: StateFlow<UnlockState> = _unlockState

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private var timeoutJob: Job? = null

    fun requestAccess() {
        if (_unlockState.value is UnlockState.Requesting) return
        scope.launch {
            val settings = prefs.settings.first()
            val supervisorPubkey = settings.supervisorPubkey
            if (supervisorPubkey.isEmpty()) return@launch
            val (privHex, pubHex) = keyManager.ensureKeypair()
            val requestId = UUID.randomUUID().toString()
            val payload = json.encodeToString(
                UnlockRequestPayload(
                    requestId = requestId,
                    deviceName = settings.displayName.ifBlank { pubHex.take(8) }
                )
            )
            val encrypted = crypto.nip04Encrypt(crypto.hexToBytes(privHex), supervisorPubkey, payload)
            val event = NostrEvent.build(
                privKeyHex = privHex,
                pubKeyHex = pubHex,
                kind = NostrEventKind.SUPERVISED_UNLOCK_REQUEST,
                content = encrypted,
                tags = listOf(listOf("p", supervisorPubkey)),
                crypto = crypto
            )
            relayClient.publishEvent(event)
            _unlockState.value = UnlockState.Requesting(requestId)
            timeoutJob?.cancel()
            timeoutJob = scope.launch {
                delay(60_000)
                if (_unlockState.value is UnlockState.Requesting) {
                    _unlockState.value = UnlockState.TimedOut
                }
            }
        }
    }

    fun handleResponse(requestId: String, approved: Boolean) {
        val current = _unlockState.value
        if (current is UnlockState.Requesting && current.requestId == requestId) {
            timeoutJob?.cancel()
            _unlockState.value = if (approved) UnlockState.Approved else UnlockState.Denied
        }
    }

    fun reset() {
        timeoutJob?.cancel()
        _unlockState.value = UnlockState.Idle
    }
}
