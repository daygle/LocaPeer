package com.locapeer.messaging

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Wire format for image / voice / file messages. Like [GroupWire], a media message is an ordinary
 * NIP-44 encrypted DM (kind ENCRYPTED_DM) whose decrypted plaintext is this envelope, distinguished
 * from plain text by a leading [MAGIC] tag immediately followed by the JSON object. Plain text
 * messages stay raw, so older clients and the location-pin detection keep working untouched.
 *
 * The media bytes travel Base64-encoded inside [data] - aggressively downscaled/compressed and
 * size-capped before sending (see MediaUtils), so the payload stays within relay limits and the
 * zero-server E2E model is preserved (each recipient gets their own individually encrypted copy).
 * Files are NOT transcodable, so they are simply rejected above [MediaUtils.MAX_FILE_BYTES] rather
 * than downscaled - only genuinely small attachments (a contact card, a short note, a tiny PDF)
 * fit the per-recipient relay budget. Video is intentionally unsupported: any clip small enough to
 * inline would be unusably short/low-res, and the only alternative is out-of-band hosting, which
 * would break the zero-server model.
 */
@Serializable
data class MediaMessage(
    /** [MediaKind.IMAGE], [MediaKind.AUDIO], or [MediaKind.FILE]. */
    val kind: String,
    /** Base64 (NO_WRAP) of the media bytes: JPEG for images, AAC/m4a for audio, raw bytes for files. */
    val data: String,
    /** Audio only: recording length in milliseconds. */
    val durationMs: Long? = null,
    /** File only: original display name (e.g. "notes.pdf"), used for the bubble label and on open. */
    val filename: String? = null,
    /** File only: MIME type (e.g. "application/pdf"), used to launch the right viewer on open. */
    val mimeType: String? = null
)

object MediaKind {
    const val IMAGE = "image"
    const val AUDIO = "audio"
    const val FILE = "file"
}

object MediaWire {
    /** Envelope tag ("LPM1"). It is ordinary printable text, so the `{`-follows check in
     *  [isEnvelope] and the JSON parse in [decode] are what actually distinguish an envelope
     *  from a plain message that merely starts with "LPM1". */
    private const val MAGIC = "LPM1"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(message: MediaMessage): String = MAGIC + json.encodeToString(MediaMessage.serializer(), message)

    /** True when [plaintext] looks like a media envelope - the [MAGIC] tag immediately followed by
     *  the opening `{` of the JSON body - even if the body itself fails to decode. Lets callers
     *  avoid rendering a raw (possibly corrupted) envelope as message text, without misfiring on a
     *  plain message a user simply typed starting with "LPM1". */
    fun isEnvelope(plaintext: String): Boolean =
        plaintext.startsWith(MAGIC) && plaintext.getOrNull(MAGIC.length) == '{'

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
