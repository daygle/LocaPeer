package com.locapeer.supervised

import com.locapeer.crypto.CryptoUtils
import com.locapeer.crypto.KeyManager
import com.locapeer.nostr.NostrEvent
import com.locapeer.nostr.NostrEventKind
import com.locapeer.nostr.NostrRelayClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SupervisionApprovalManager @Inject constructor(
    private val keyManager: KeyManager,
    private val crypto: CryptoUtils,
    private val relayClient: NostrRelayClient
) {
    data class PendingRequest(val fromPubkey: String, val deviceName: String, val requestId: String)

    private val _pending = MutableStateFlow<PendingRequest?>(null)
    val pending: StateFlow<PendingRequest?> = _pending

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    fun setPending(request: PendingRequest) {
        _pending.value = request
    }

    fun respond(approved: Boolean) {
        val request = _pending.value ?: return
        _pending.value = null
        scope.launch {
            val (privHex, pubHex) = keyManager.ensureKeypair()
            val payload = json.encodeToString(
                UnlockResponsePayload(requestId = request.requestId, approved = approved)
            )
            val encrypted = crypto.nip44Encrypt(crypto.hexToBytes(privHex), request.fromPubkey, payload)
            val event = NostrEvent.build(
                privKeyHex = privHex,
                pubKeyHex = pubHex,
                kind = NostrEventKind.SUPERVISED_UNLOCK_RESPONSE,
                content = encrypted,
                tags = listOf(listOf("p", request.fromPubkey)),
                crypto = crypto
            )
            relayClient.publishEvent(event)
        }
    }
}
