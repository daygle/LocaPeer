package com.locapeer.invite

import android.util.Log
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.locapeer.crypto.KeyManager
import com.locapeer.settings.AppPreferences
import com.locapeer.supervised.ContactAddApprovalManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import javax.inject.Inject

private const val MAX_INVITE_NAME_LENGTH = 64

data class ScanState(
    val success: Boolean = false,
    val addedName: String = "",
    val error: String? = null,
    /** Name of a parsed-but-not-yet-confirmed invite awaiting explicit user approval. */
    val pendingName: String? = null,
    /** True while the supervisor is deciding a supervised contact-add request (kind 1067). */
    val awaitingSupervisor: Boolean = false,
)

@HiltViewModel
class ScanViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val keyManager: KeyManager,
    private val prefs: AppPreferences,
    private val contactAddApprovalManager: ContactAddApprovalManager,
    private val executor: ContactAddExecutor,
) : ViewModel() {

    private val _scanState = MutableStateFlow(ScanState())
    val scanState: StateFlow<ScanState> = _scanState

    private var processed = false
    private val json = Json { ignoreUnknownKeys = true }

    /** Parsed invite held between scan/deep-link and the user's explicit confirmation. */
    private var pendingInvite: InviteData? = null

    /**
     * Parse and validate an invite, then stage it for explicit confirmation instead of
     * adding the contact immediately. Establishing two-way location sharing must be a
     * deliberate user action - a scanned QR or, more importantly, a `locapeer://` deep
     * link that a webpage or message could trigger, must not silently add a contact.
     */
    fun processQrCode(raw: String) {
        if (processed) return
        processed = true
        try {
            val invite = json.decodeFromString<InviteData>(raw)
            if (!isValidPubKeyHex(invite.publicKeyHex) || !isValidPubKeyHex(invite.deviceId)) {
                _scanState.value = ScanState(error = context.getString(com.locapeer.R.string.scan_error_bad_pubkey))
                processed = false
                return
            }
            // A device is identified by its Nostr key: deviceId and publicKeyHex must be the
            // same key. Rejecting a mismatch prevents a crafted invite from upserting an
            // existing peer row (keyed by deviceId) while pointing publicKeyHex at an
            // attacker-controlled key - which would hijack whose key we encrypt to/verify from.
            if (!invite.publicKeyHex.equals(invite.deviceId, ignoreCase = true)) {
                _scanState.value = ScanState(error = context.getString(com.locapeer.R.string.scan_error_key_mismatch))
                processed = false
                return
            }
            // Nostr serializes pubkeys as lowercase hex; normalize so the stored peer row
            // matches later events (and dedupes against an existing lowercase row).
            val canonicalKey = invite.publicKeyHex.lowercase()
            // The display name is attacker-controlled (a deep link can carry anything); bound it
            // before it reaches the confirmation dialog, the contact row and notifications.
            pendingInvite = invite.copy(
                publicKeyHex = canonicalKey,
                deviceId = canonicalKey,
                displayName = invite.displayName.trim().take(MAX_INVITE_NAME_LENGTH),
            )
            _scanState.value = ScanState(
                pendingName = pendingInvite?.displayName?.ifBlank { null }
                    ?: context.getString(com.locapeer.R.string.scan_fallback_contact)
            )
        } catch (e: Exception) {
            _scanState.value = ScanState(error = e.message ?: context.getString(com.locapeer.R.string.scan_error_unknown))
            processed = false
        }
    }

    /** Commit the staged invite: add the contact and send the track request.
     *  While supervised this is a per-action decision: the supervisor is asked (kind
     *  1067) to approve adding THIS contact by name, and the add only happens after
     *  their answer - scanning a QR can no longer create a new sharing destination
     *  behind their back. */
    fun confirmAdd() {
        val invite = pendingInvite ?: return
        viewModelScope.launch {
            try {
                // Adding yourself would make every heartbeat and message loop back to this device.
                if (invite.publicKeyHex == keyManager.ensureKeypair().second) {
                    pendingInvite = null
                    processed = false
                    _scanState.value = ScanState(error = context.getString(com.locapeer.R.string.scan_error_own_invite))
                    return@launch
                }
                if (prefs.settings.first().supervisionActive) {
                    // Set waiting first: requestAdd may finish synchronously if supervision
                    // ended since the check, or if sending the request fails.
                    _scanState.value = ScanState(awaitingSupervisor = true)
                    contactAddApprovalManager.requestAdd(
                        contactPubkeyHex = invite.publicKeyHex,
                        contactName = invite.displayName,
                        action = ContactAddApprovalManager.AddAction.OutboundScan(invite),
                    ) { approved ->
                        // UI feedback only: the durable add runs in the approval manager
                        // (ContactAddExecutor), so navigation or a process kill mid-wait
                        // cannot orphan an approved add.
                        if (approved) {
                            pendingInvite = null
                            processed = true
                            _scanState.value = ScanState(success = true, addedName = invite.displayName)
                        } else {
                            processed = false
                            _scanState.value = ScanState(
                                error = context.getString(com.locapeer.R.string.scan_add_denied)
                            )
                        }
                    }
                } else {
                    executor.performOutboundAdd(invite)
                    pendingInvite = null
                    processed = true
                    _scanState.value = ScanState(success = true, addedName = invite.displayName)
                }
            } catch (e: Exception) {
                _scanState.value = ScanState(error = e.message ?: context.getString(com.locapeer.R.string.scan_error_unknown))
                processed = false
            }
        }
    }

    private fun isValidPubKeyHex(hex: String): Boolean =
        hex.length == 64 && hex.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }

    fun processInviteLink(input: String) {
        try {
            val data = if (input.startsWith("locapeer://")) {
                val uri = input.toUri()
                uri.getQueryParameter("data") ?: throw Exception("Missing data parameter")
            } else {
                input
            }
            // URL_SAFE + NO_WRAP covers the Base64.getUrlEncoder().withoutPadding() output.
            val decodedBytes = android.util.Base64.decode(data, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP)
            val raw = String(decodedBytes, Charsets.UTF_8)
            processQrCode(raw)
        } catch (e: Exception) {
            Log.e("ScanViewModel", "Invite link processing failed", e)
            _scanState.value = ScanState(error = context.getString(com.locapeer.R.string.scan_error_invalid_link))
        }
    }

    fun reset() {
        processed = false
        pendingInvite = null
        _scanState.value = ScanState()
    }
}
