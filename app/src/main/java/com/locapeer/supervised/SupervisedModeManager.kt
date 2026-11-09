package com.locapeer.supervised

import com.locapeer.crypto.CryptoUtils
import com.locapeer.util.backgroundScope
import com.locapeer.crypto.KeyManager
import com.locapeer.nostr.NostrEvent
import com.locapeer.nostr.NostrEventKind
import com.locapeer.nostr.NostrRelayClient
import com.locapeer.settings.AppPreferences
import com.locapeer.settings.HARDCODED_RELAYS
import kotlinx.coroutines.Job
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

    /** Disable flow: supervised mode is released only after the supervisor answers a
     *  {@link NostrEventKind#SUPERVISED_DISABLE_REQUEST kind-1064} request. Until that
     *  answer the device stays gated - a local tap can no longer end supervision (it
     *  used to, silently, which is exactly what the supervisor model must prevent). */
    sealed class DisableState {
        object Idle : DisableState()
        data class Requesting(val requestId: String) : DisableState()
        object Denied : DisableState()
        object TimedOut : DisableState()
    }

    private val _disableState = MutableStateFlow<DisableState>(DisableState.Idle)
    val disableState: StateFlow<DisableState> = _disableState

    private val scope = backgroundScope("SupervisedModeManager")
    private val json = Json { ignoreUnknownKeys = true }
    private var timeoutJob: Job? = null
    private var disableTimeoutJob: Job? = null

    /** Guards reserving a request id and its Requesting state as one step (see [requestAccess]). */
    private val requestLock = Any()

    fun requestAccess() {
        val requestId = UUID.randomUUID().toString()
        // Reserve the id and state before publishing. Two taps can no longer start two requests,
        // and an approval that arrives while the event is still being sent is matched by id
        // instead of landing while the state is still Idle and being dropped.
        if (!beginUnlockRequest(requestId)) return
        scope.launch { sendUnlockRequest(requestId) }
    }

    private fun beginUnlockRequest(requestId: String): Boolean {
        synchronized(requestLock) {
            if (_unlockState.value is UnlockState.Requesting) return false
            _unlockState.value = UnlockState.Requesting(requestId)
            timeoutJob?.cancel()
            timeoutJob = scope.launch {
                delay(60_000)
                if (_unlockState.value is UnlockState.Requesting) {
                    _unlockState.value = UnlockState.TimedOut
                }
            }
        }
        return true
    }

    private suspend fun sendUnlockRequest(requestId: String) {
        val settings = prefs.settings.first()
        val supervisorPubkey = settings.supervisorPubkey
        // Not supervised any more: drop the reservation instead of leaving the UI waiting on an
        // answer that was never requested.
        if (supervisorPubkey.isEmpty()) return abandonUnlockRequest(requestId)
        // The supervisor may already have answered while this request was being prepared.
        if (unlockRequestId() != requestId) return
        try {
            val (privHex, pubHex) = keyManager.ensureKeypair()
            val payload = json.encodeToString(
                UnlockRequestPayload(
                    requestId = requestId,
                    deviceName = settings.displayName.ifBlank { pubHex.take(8) }
                )
            )
            val encrypted = crypto.nip44Encrypt(crypto.hexToBytes(privHex), supervisorPubkey, payload)
            val event = NostrEvent.build(
                privKeyHex = privHex,
                pubKeyHex = pubHex,
                kind = NostrEventKind.SUPERVISED_UNLOCK_REQUEST,
                content = encrypted,
                tags = listOf(listOf("p", supervisorPubkey)),
                crypto = crypto
            )
            relayClient.publishEvent(event)
        } catch (e: Exception) {
            android.util.Log.w("SupervisedModeManager", "Failed to send unlock request", e)
            abandonUnlockRequest(requestId)
        }
    }

    private fun unlockRequestId(): String? =
        (_unlockState.value as? UnlockState.Requesting)?.requestId

    /** Clears a reservation that never reached the relay (or was answered before it did). */
    private fun abandonUnlockRequest(requestId: String) {
        synchronized(requestLock) {
            if (unlockRequestId() != requestId) return
            timeoutJob?.cancel()
            _unlockState.value = UnlockState.Idle
        }
    }

    fun handleResponse(requestId: String, approved: Boolean) {
        val current = _unlockState.value
        if (current is UnlockState.Requesting && current.requestId == requestId) {
            timeoutJob?.cancel()
            _unlockState.value = if (approved) UnlockState.Approved else UnlockState.Denied
        }
    }

    /** Ask the supervisor to release supervised mode. Approval arrives as kind 1065
     *  (see [handleDisableResponse]); the local state is never cleared optimistically.
     *  A pending (not accepted) request can still be cancelled freely by the UI -
     *  supervision isn't in force yet. */
    fun requestDisable() {
        val requestId = UUID.randomUUID().toString()
        // Same reservation as unlock: the supervisor's 1065 answer can otherwise race the publish
        // and be ignored, leaving supervision gated until the timeout.
        if (!beginDisableRequest(requestId)) return
        scope.launch { sendDisableRequest(requestId) }
    }

    private fun beginDisableRequest(requestId: String): Boolean {
        synchronized(requestLock) {
            if (_disableState.value is DisableState.Requesting) return false
            _disableState.value = DisableState.Requesting(requestId)
            disableTimeoutJob?.cancel()
            disableTimeoutJob = scope.launch {
                delay(60_000)
                if (_disableState.value is DisableState.Requesting) {
                    _disableState.value = DisableState.TimedOut
                }
            }
        }
        return true
    }

    private suspend fun sendDisableRequest(requestId: String) {
        val settings = prefs.settings.first()
        val supervisorPubkey = settings.supervisorPubkey
        // Only an active (accepted) supervision needs the supervisor's release; the UI cancels
        // pending requests locally.
        if (supervisorPubkey.isEmpty() || !settings.supervisionActive) return abandonDisableRequest(requestId)
        if (disableRequestId() != requestId) return
        try {
            val (privHex, pubHex) = keyManager.ensureKeypair()
            val payload = json.encodeToString(
                SupervisedDisableRequestPayload(
                    requestId = requestId,
                    deviceName = settings.displayName.ifBlank { pubHex.take(8) }
                )
            )
            val encrypted = crypto.nip44Encrypt(crypto.hexToBytes(privHex), supervisorPubkey, payload)
            val event = NostrEvent.build(
                privKeyHex = privHex,
                pubKeyHex = pubHex,
                kind = NostrEventKind.SUPERVISED_DISABLE_REQUEST,
                content = encrypted,
                tags = listOf(listOf("p", supervisorPubkey)),
                crypto = crypto
            )
            relayClient.publishEvent(event)
        } catch (e: Exception) {
            android.util.Log.w("SupervisedModeManager", "Failed to send disable request", e)
            abandonDisableRequest(requestId)
        }
    }

    private fun disableRequestId(): String? =
        (_disableState.value as? DisableState.Requesting)?.requestId

    private fun abandonDisableRequest(requestId: String) {
        synchronized(requestLock) {
            if (disableRequestId() != requestId) return
            disableTimeoutJob?.cancel()
            _disableState.value = DisableState.Idle
        }
    }

    fun cancelDisableRequest() {
        disableTimeoutJob?.cancel()
        _disableState.value = DisableState.Idle
    }

    /** The supervisor's answer to our disable request. Approving ends supervised mode
     *  locally (and symmetrically: the supervisor clears its own isMySupervised flag on
     *  the same action); a denial keeps supervision in force, so a retry clock cannot
     *  brute-force its way out of the gate. */
    fun handleDisableResponse(requestId: String, approved: Boolean) {
        val current = _disableState.value
        if (current is DisableState.Requesting && current.requestId == requestId) {
            disableTimeoutJob?.cancel()
            if (approved) {
                _disableState.value = DisableState.Idle
                scope.launch { prefs.clearSupervisedMode() }
            } else {
                _disableState.value = DisableState.Denied
            }
        }
    }

    fun reset() {
        synchronized(requestLock) {
            timeoutJob?.cancel()
            _unlockState.value = UnlockState.Idle
            disableTimeoutJob?.cancel()
            _disableState.value = DisableState.Idle
        }
    }

    fun sendRegisterRequest(supervisorPubkey: String) {
        scope.launch {
            val settings = prefs.settings.first()
            val (privHex, pubHex) = keyManager.ensureKeypair()
            // Prefer the first connected relay, fall back to the first hardcoded one.
            val myRelay = relayClient.relayStatus.value.filter { it.value }.keys.firstOrNull()
                ?: HARDCODED_RELAYS.first()
            val payload = json.encodeToString(
                SupervisedRegisterPayload(
                    devicePubkeyHex = pubHex,
                    deviceName = settings.displayName.ifBlank { pubHex.take(8) },
                    deviceRelayUrl = myRelay
                )
            )
            val encrypted = crypto.nip44Encrypt(crypto.hexToBytes(privHex), supervisorPubkey, payload)
            val event = com.locapeer.nostr.NostrEvent.build(
                privKeyHex = privHex,
                pubKeyHex = pubHex,
                kind = com.locapeer.nostr.NostrEventKind.SUPERVISED_REGISTER,
                content = encrypted,
                tags = listOf(listOf("p", supervisorPubkey)),
                crypto = crypto
            )
            relayClient.publishEvent(event)
        }
    }

    /** Best-effort kind-1066 notice to the supervisor that this device is erasing its
     *  identity, sent while the signing key still exists. Fire-and-forget: the erase
     *  flow proceeds immediately whether or not delivery succeeds (which is also why
     *  the wipe alert is not an approval - it only makes the escape visible). */
    fun sendWipeNotice() {
        scope.launch {
            val settings = prefs.settings.first()
            if (!settings.supervisionActive) return@launch
            val supervisorPubkey = settings.supervisorPubkey
            if (supervisorPubkey.isEmpty()) return@launch
            try {
                val (privHex, pubHex) = keyManager.ensureKeypair()
                val payload = json.encodeToString(SupervisedWipePayload(devicePubkeyHex = pubHex))
                val encrypted = crypto.nip44Encrypt(crypto.hexToBytes(privHex), supervisorPubkey, payload)
                relayClient.publishEvent(
                    NostrEvent.build(
                        privKeyHex = privHex,
                        pubKeyHex = pubHex,
                        kind = com.locapeer.nostr.NostrEventKind.SUPERVISED_WIPE_NOTICE,
                        content = encrypted,
                        tags = listOf(listOf("p", supervisorPubkey)),
                        crypto = crypto
                    )
                )
            } catch (e: Exception) {
                android.util.Log.w("SupervisedModeManager", "Failed to send wipe notice", e)
            }
        }
    }
}
