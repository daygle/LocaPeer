package com.locapeer.supervised

import com.locapeer.crypto.CryptoUtils
import com.locapeer.util.backgroundScope
import com.locapeer.crypto.KeyManager
import com.locapeer.nostr.NostrEvent
import com.locapeer.nostr.NostrEventKind
import com.locapeer.nostr.NostrRelayClient
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
    /** What the supervised device is asking for: unlock its settings session, release
     *  supervised mode entirely, or add a new tracking destination. Same approval surface,
     *  different response kind. */
    enum class RequestType { UNLOCK, DISABLE, CONTACT_ADD }

    data class PendingRequest(
        val fromPubkey: String,
        val deviceName: String,
        val requestId: String,
        val type: RequestType = RequestType.UNLOCK
    )

    private val _pending = MutableStateFlow<PendingRequest?>(null)
    val pending: StateFlow<PendingRequest?> = _pending

    private val scope = backgroundScope("SupervisionApproval")
    private val json = Json { ignoreUnknownKeys = true }

    fun setPending(request: PendingRequest) {
        _pending.value = request
    }

    fun respond(approved: Boolean) {
        val request = _pending.value ?: return
        _pending.value = null
        scope.launch {
            // An uncaught throw in this bare scope would crash the process.
            try {
                val (privHex, pubHex) = keyManager.ensureKeypair()
                val payload: String
                val kind: Int
                when (request.type) {
                    RequestType.UNLOCK -> {
                        payload = json.encodeToString(
                            UnlockResponsePayload(requestId = request.requestId, approved = approved)
                        )
                        kind = NostrEventKind.SUPERVISED_UNLOCK_RESPONSE
                    }
                    RequestType.DISABLE -> {
                        payload = json.encodeToString(
                            SupervisedDisableResponsePayload(requestId = request.requestId, approved = approved)
                        )
                        kind = NostrEventKind.SUPERVISED_DISABLE_RESPONSE
                    }
                    RequestType.CONTACT_ADD -> {
                        payload = json.encodeToString(
                            ContactAddResponsePayload(requestId = request.requestId, approved = approved)
                        )
                        kind = NostrEventKind.CONTACT_ADD_RESPONSE
                    }
                }
                val encrypted = crypto.nip44Encrypt(crypto.hexToBytes(privHex), request.fromPubkey, payload)
                val event = NostrEvent.build(
                    privKeyHex = privHex,
                    pubKeyHex = pubHex,
                    kind = kind,
                    content = encrypted,
                    tags = listOf(listOf("p", request.fromPubkey)),
                    crypto = crypto
                )
                relayClient.publishEvent(event)
            } catch (e: Exception) {
                android.util.Log.w("SupervisionApproval", "Failed to send unlock response", e)
            }
        }
    }
}
