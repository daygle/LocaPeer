package com.locapeer.messaging

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Wire format for image / voice messages. Like [GroupWire], a media message is an ordinary NIP-44
 * encrypted DM (kind ENCRYPTED_DM) whose decrypted plaintext is this envelope, distinguished from
 * plain text by a leading control-character [MAGIC]. Plain text messages stay raw, so older clients
 * and the location-pin detection keep working untouched.
 *
 * The media bytes travel Base64-encoded inside [data] — aggressively downscaled/compressed and
 * size-capped before sending (see MediaUtils), so the payload stays within relay limits and the
 * zero-server E2E model is preserved (each recipient gets their own individually encrypted copy).
 */
@Serializable
data class MediaMessage(
    /** [MediaKind.IMAGE] or [MediaKind.AUDIO]. */
    val kind: String,
    /** Base64 (NO_WRAP) of the compressed media bytes: JPEG for images, AAC/m4a for audio. */
    val data: String,
    /** Audio only: recording length in milliseconds. */
    val durationMs: Long? = null
)

object MediaKind {
    const val IMAGE = "image"
    const val AUDIO = "audio"
}

object MediaWire {
    /** Control char + tag; a user can't type U+0001, so this never collides with real text. */
    private const val MAGIC = "LPM1"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(message: MediaMessage): String = MAGIC + json.encodeToString(MediaMessage.serializer(), message)

    /** Returns the decoded media message, or null when [plaintext] is not a media envelope. */
    fun decode(plaintext: String): MediaMessage? {
        if (!plaintext.startsWith(MAGIC)) return null
        return try {
            json.decodeFromString(MediaMessage.serializer(), plaintext.substring(MAGIC.length))
        } catch (e: Exception) {
            null
        }
    }
}
