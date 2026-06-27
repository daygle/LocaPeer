package com.locapeer.nostr

import com.locapeer.crypto.CryptoUtils
import java.nio.charset.StandardCharsets
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray

object NostrEventKind {
    const val ENCRYPTED_DM = 4
    const val HEARTBEAT = 1
    const val SOS_ALERT = 30000
    const val READ_RECEIPT = 10001   // NIP-04-encrypted ack listing seen event IDs
    const val TYPING = 10002         // NIP-04-encrypted ephemeral typing signal
    const val PURGE_REQUEST = 10003         // NIP-04-encrypted request to delete old heartbeats
    const val MESSAGE_PURGE_REQUEST = 10004 // NIP-04-encrypted request to delete old messages
    const val DELIVERY_ACK = 10005          // NIP-04-encrypted delivery acknowledgment
    const val SUPERVISED_UNLOCK_REQUEST = 10006  // NIP-04-encrypted unlock request to supervisor
    const val SUPERVISED_UNLOCK_RESPONSE = 10007 // NIP-04-encrypted unlock response from supervisor
    const val TRACK_REQUEST = 10008  // NIP-44-encrypted mutual tracking invitation
    const val TRACK_ACCEPT = 10009   // NIP-44-encrypted acceptance of a track request
    const val PEER_REMOVED = 10010   // NIP-44-encrypted peer removal notification
    const val DELETE_MY_MESSAGES = 10011  // NIP-44-encrypted request to delete all sender's messages
    const val DELETE_MY_LOCATION = 10012  // NIP-44-encrypted request to delete all sender's heartbeats
}

@Serializable
data class NostrEvent(
    val id: String = "",
    val pubkey: String = "",
    @SerialName("created_at") val createdAt: Long = 0L,
    val kind: Int = 0,
    val tags: List<List<String>> = emptyList(),
    val content: String = "",
    val sig: String = ""
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun build(
            privKeyHex: String,
            pubKeyHex: String,
            kind: Int,
            content: String,
            tags: List<List<String>> = emptyList(),
            crypto: CryptoUtils
        ): NostrEvent {
            val createdAt = System.currentTimeMillis() / 1000L
            val serialized = serializeForId(pubKeyHex, createdAt, kind, tags, content)
            val idBytes = crypto.sha256(serialized.toByteArray(StandardCharsets.UTF_8))
            val idHex = crypto.bytesToHex(idBytes)
            val privBytes = crypto.hexToBytes(privKeyHex)
            val sigBytes = crypto.schnorrSign(idBytes, privBytes)
            val sigHex = crypto.bytesToHex(sigBytes)
            return NostrEvent(
                id = idHex,
                pubkey = pubKeyHex,
                createdAt = createdAt,
                kind = kind,
                tags = tags,
                content = content,
                sig = sigHex
            )
        }

        private fun serializeForId(
            pubkey: String,
            createdAt: Long,
            kind: Int,
            tags: List<List<String>>,
            content: String
        ): String {
            val tagsJson = buildJsonArray {
                tags.forEach { tag ->
                    add(buildJsonArray { tag.forEach { add(JsonPrimitive(it)) } })
                }
            }
            val arr: JsonArray = buildJsonArray {
                add(JsonPrimitive(0))
                add(JsonPrimitive(pubkey))
                add(JsonPrimitive(createdAt))
                add(JsonPrimitive(kind))
                add(tagsJson)
                add(JsonPrimitive(content))
            }
            return json.encodeToString(JsonElement.serializer(), arr)
        }

        fun verify(event: NostrEvent, crypto: CryptoUtils): Boolean {
            val serialized = serializeForId(event.pubkey, event.createdAt, event.kind, event.tags, event.content)
            val idBytes = crypto.sha256(serialized.toByteArray(StandardCharsets.UTF_8))
            val idHex = crypto.bytesToHex(idBytes)
            if (idHex != event.id) return false
            val sigBytes = crypto.hexToBytes(event.sig)
            val pubBytes = crypto.hexToBytes(event.pubkey)
            return crypto.schnorrVerify(sigBytes, idBytes, pubBytes)
        }
    }
}

/** Nostr wire protocol message wrappers. */
@Serializable
sealed class NostrMessage

data class EventMessage(val event: NostrEvent) : NostrMessage()
data class ReqMessage(val subscriptionId: String, val filters: List<NostrFilter>) : NostrMessage()
data class CloseMessage(val subscriptionId: String) : NostrMessage()

@Serializable
data class NostrFilter(
    val authors: List<String>? = null,
    val kinds: List<Int>? = null,
    val since: Long? = null,
    val until: Long? = null,
    val limit: Int? = null,
    @SerialName("#p") val pTags: List<String>? = null,
    @SerialName("#t") val tTags: List<String>? = null
)
