package com.locapeer.invite

import android.content.Context
import com.locapeer.R
import com.locapeer.crypto.CryptoUtils
import com.locapeer.crypto.KeyManager
import com.locapeer.nostr.NostrEvent
import com.locapeer.nostr.NostrEventKind
import com.locapeer.nostr.NostrRelayClient
import com.locapeer.settings.AppPreferences
import com.locapeer.settings.HARDCODED_RELAYS
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds and publishes TRACK_ACCEPT / TRACK_DECLINE responses. One implementation for
 * the four places that answer a track request: the incoming-request screen, the pending
 * requests list, the notification decline action, and HeartbeatReceiver's auto-promote.
 */
@Singleton
class TrackResponseSender @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keyManager: KeyManager,
    private val crypto: CryptoUtils,
    private val relayClient: NostrRelayClient,
    private val prefs: AppPreferences,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Publish a TRACK_ACCEPT granting [acceptedRole], connecting to [recipientRelay]
     * first (when known) so the response reaches the requester's relay.
     */
    suspend fun sendAccept(recipientPubkey: String, recipientRelay: String, acceptedRole: String) {
        val (privHex, pubHex) = keyManager.ensureKeypair()
        val payload = TrackAcceptPayload(
            acceptorPublicKeyHex = pubHex,
            acceptorDisplayName = displayNameOrFallback(),
            acceptorDeviceId = pubHex,
            acceptorRelayUrl = HARDCODED_RELAYS.first(),
            acceptedRole = acceptedRole
        )
        publish(recipientPubkey, recipientRelay, NostrEventKind.TRACK_ACCEPT, json.encodeToString(payload), privHex, pubHex)
    }

    /** Publish a TRACK_DECLINE; [isRoleChange] tells the requester whether to keep the contact. */
    suspend fun sendDecline(recipientPubkey: String, recipientRelay: String, isRoleChange: Boolean) {
        val (privHex, pubHex) = keyManager.ensureKeypair()
        val payload = TrackDeclinePayload(
            declinerPublicKeyHex = pubHex,
            declinerDisplayName = displayNameOrFallback(),
            declinerDeviceId = pubHex,
            isRoleChange = isRoleChange
        )
        publish(recipientPubkey, recipientRelay, NostrEventKind.TRACK_DECLINE, json.encodeToString(payload), privHex, pubHex)
    }

    private suspend fun displayNameOrFallback(): String =
        prefs.settings.first().displayName.ifBlank { context.getString(R.string.notif_someone) }

    private fun publish(
        recipientPubkey: String,
        recipientRelay: String,
        kind: Int,
        plaintext: String,
        privHex: String,
        pubHex: String,
    ) {
        if (recipientRelay.isNotBlank()) relayClient.connect(recipientRelay)
        val encrypted = crypto.nip44Encrypt(crypto.hexToBytes(privHex), recipientPubkey, plaintext)
        relayClient.publishEvent(
            NostrEvent.build(
                privKeyHex = privHex,
                pubKeyHex = pubHex,
                kind = kind,
                content = encrypted,
                tags = listOf(listOf("p", recipientPubkey)),
                crypto = crypto
            )
        )
    }
}
