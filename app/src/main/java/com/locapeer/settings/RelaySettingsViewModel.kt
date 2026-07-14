package com.locapeer.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.locapeer.nostr.NostrRelayClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs the relay-management screen: exposes the effective connection status per relay,
 * the user's custom relays and public-relay toggle, and validated mutations. Relay-list
 * changes are picked up live by [NostrRelayClient] (it observes the same preferences), so
 * saving here reconfigures connections without an app restart.
 */
@HiltViewModel
class RelaySettingsViewModel @Inject constructor(
    val prefs: AppPreferences,
    relayClient: NostrRelayClient,
) : ViewModel() {

    val relayStatus: StateFlow<Map<String, Boolean>> = relayClient.relayStatus
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val builtInPublicRelays: List<String> = PUBLIC_RELAYS
    val primaryRelay: String = PRIMARY_RELAY

    /**
     * Validate and add a custom relay. Returns an error string key for the caller to show,
     * or null on success. Only `wss://` endpoints are accepted (mirroring
     * [NostrRelayClient]'s own guard - a `ws://` relay would carry traffic in cleartext),
     * and duplicates of an existing custom or built-in relay are rejected.
     */
    fun addCustomRelay(raw: String, current: List<String>): RelayAddResult {
        val url = raw.trim()
        if (!isValidWssUrl(url)) return RelayAddResult.INVALID
        val normalized = url.trimEnd('/')
        val existing = (current + PRIMARY_RELAY + PUBLIC_RELAYS).map { it.trimEnd('/').lowercase() }
        if (normalized.lowercase() in existing) return RelayAddResult.DUPLICATE
        viewModelScope.launch { prefs.setCustomRelays(current + normalized) }
        return RelayAddResult.OK
    }

    fun removeCustomRelay(url: String, current: List<String>) {
        viewModelScope.launch { prefs.setCustomRelays(current - url) }
    }

    fun setUsePublicRelays(enabled: Boolean) {
        viewModelScope.launch { prefs.setUsePublicRelays(enabled) }
    }

    private fun isValidWssUrl(url: String): Boolean {
        val uri = try { java.net.URI(url) } catch (_: Exception) { return false }
        return uri.scheme?.equals("wss", ignoreCase = true) == true && !uri.host.isNullOrBlank()
    }

    enum class RelayAddResult { OK, INVALID, DUPLICATE }
}
