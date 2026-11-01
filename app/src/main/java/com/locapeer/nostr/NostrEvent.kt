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
    const val ENCRYPTED_DM = 44
    const val EVENT_DELETION = 5
    const val HEARTBEAT = 1040
    const val SOS_ALERT = 1041
    const val TRACK_REQUEST = 1042
    const val TRACK_ACCEPT = 1043
    const val PEER_REMOVED = 1044
    const val PURGE_REQUEST = 1045
    const val MESSAGE_PURGE_REQUEST = 1046
    const val DELIVERY_ACK = 1047
    const val READ_RECEIPT = 1048
    const val SUPERVISED_UNLOCK_REQUEST = 1049
    const val SUPERVISED_UNLOCK_RESPONSE = 1050
    const val DELETE_MY_MESSAGES = 1051
    const val DELETE_MY_LOCATION = 1052
    const val TYPING = 1053
    const val TRACK_DECLINE = 1054
    const val SUPERVISED_REGISTER = 1055
    const val SUPERVISED_REGISTER_ACCEPT = 1056
    const val SUPERVISED_REGISTER_DECLINE = 1057
    const val TRACKING_ALERT = 1058
    const val CIRCLE_LEAVE = 1059
    // Sent by a viewer while they have the map open, asking the recipient to broadcast
    // location at a fast "live" cadence for a short lease. Purely a rate hint - it never
    // grants access the recipient's role/pause settings don't already allow.
    const val LIVE_VIEW_REQUEST = 1060

    // ── Supervised-mode state changes (1061-1066) ─────────────────────────———
    // Every supervision state change after establishment is now in-band and two-sided:
    // the handshake kinds (1055-1057) remain the primary path, these act on the edges.

    /** Supervisor -> device: "I would like to supervise you". Requires device consent (1062 or 1055). */
    const val SUPERVISED_INVITE = 1061

    /** Device -> supervisor: answer to a 1061 invite (accepted=false is the meaningful signal;
     *  accepted=true is immediately followed by a 1055 register request which activates it). */
    const val SUPERVISED_INVITE_RESPONSE = 1062

    /** Supervisor -> device: supervision ended from the supervisor side without removing the contact. */
    const val SUPERVISED_SUPERVISOR_END = 1063

    /** Device -> supervisor: request to disable supervised mode (mirrors the 1049/1050 unlock flow). */
    const val SUPERVISED_DISABLE_REQUEST = 1064

    /** Supervisor -> device: answer to a 1064 disable request. */
    const val SUPERVISED_DISABLE_RESPONSE = 1065

    /** Device -> supervisor: best-effort notice that this identity was erased (wipes keys). */
    const val SUPERVISED_WIPE_NOTICE = 1066

    /** Device -> supervisor (kind 1067): per-action request to add a NEW sharing destination
     *  (outbound scan/invite confirm, or accepting an inbound tracking request). This is the
     *  real consent point of supervised mode - the supervisor sees the contact's name and
     *  approves or denies the add itself. */
    const val CONTACT_ADD_REQUEST = 1067

    /** Supervisor -> device (kind 1068): answer to a 1067 contact-add request. */
    const val CONTACT_ADD_RESPONSE = 1068

    /** Device -> supervisor (kind 1069): batched summary of settings changes the supervised
     *  device made while under an approved unlock, so "what changed" is always visible. */
    const val SETTINGS_CHANGE_SUMMARY = 1069
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
            // Never throw: this runs directly in the relay event-processing pipeline, so a
            // malformed field (non-hex sig/pubkey, odd-length hex) must be rejected as an
            // invalid event rather than propagating and cancelling the event collector.
            return try {
                if (event.id.length != 64 || event.pubkey.length != 64 || event.sig.length != 128 ||
                    event.kind !in 0..65535 || event.createdAt < 0 ||
                    event.createdAt > Long.MAX_VALUE / 1000L ||
                    !event.id.all { it in '0'..'9' || it in 'a'..'f' } ||
                    !event.pubkey.all { it in '0'..'9' || it in 'a'..'f' } ||
                    !event.sig.all { it in '0'..'9' || it in 'a'..'f' }
                ) return false
                val serialized = serializeForId(event.pubkey, event.createdAt, event.kind, event.tags, event.content)
                val idBytes = crypto.sha256(serialized.toByteArray(StandardCharsets.UTF_8))
                val idHex = crypto.bytesToHex(idBytes)
                if (idHex != event.id) return false
                val sigBytes = crypto.hexToBytes(event.sig)
                val pubBytes = crypto.hexToBytes(event.pubkey)
                crypto.schnorrVerify(sigBytes, idBytes, pubBytes)
            } catch (e: Exception) {
                false
            }
        }
    }
}

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
