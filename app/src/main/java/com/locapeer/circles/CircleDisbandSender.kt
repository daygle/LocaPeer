package com.locapeer.circles

import android.util.Log
import com.locapeer.crypto.CryptoUtils
import com.locapeer.crypto.KeyManager
import com.locapeer.nostr.NostrEvent
import com.locapeer.nostr.NostrEventKind
import com.locapeer.nostr.NostrRelayClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Publishes a circle creator's [NostrEventKind.CIRCLE_DISBAND] notice, one NIP-44-encrypted copy
 * per recipient. Used both when the creator deletes a circle for everyone and when they remove a
 * member: either way the recipient's copy of the circle must go, and recipients only honour the
 * notice from the circle's recorded creator.
 */
@Singleton
class CircleDisbandSender @Inject constructor(
    private val keyManager: KeyManager,
    private val crypto: CryptoUtils,
    private val relayClient: NostrRelayClient
) {
    private val json = Json

    /** Sends the notice to each of [recipients] (our own key is skipped). Failures are logged per recipient. */
    suspend fun send(circleId: String, recipients: Collection<String>) {
        val (privHex, pubHex) = keyManager.ensureKeypair()
        val privBytes = crypto.hexToBytes(privHex)
        val payload = json.encodeToString(CircleDisbandPayload(circleId))
        withContext(Dispatchers.Default) {
            recipients.filter { !it.equals(pubHex, ignoreCase = true) }.distinct().forEach { memberPub ->
                try {
                    val event = NostrEvent.build(
                        privKeyHex = privHex,
                        pubKeyHex = pubHex,
                        kind = NostrEventKind.CIRCLE_DISBAND,
                        content = crypto.nip44Encrypt(privBytes, memberPub, payload),
                        tags = listOf(listOf("p", memberPub)),
                        crypto = crypto
                    )
                    relayClient.publishEvent(event)
                } catch (e: Exception) {
                    Log.e("CircleDisbandSender", "circle disband notice to $memberPub failed", e)
                }
            }
        }
    }
}
