package com.locapeer.messaging

import android.content.Context
import android.graphics.Bitmap
import androidx.core.graphics.scale
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.webkit.MimeTypeMap
import java.io.ByteArrayOutputStream
import java.util.Locale

/**
 * Image compression + Base64 helpers for inline media messages. Images are aggressively downscaled
 * and JPEG-compressed under [MAX_BYTES] so the Base64 payload fits comfortably inside a NIP-44
 * encrypted DM (each recipient gets their own encrypted copy). Audio arrives already compressed from
 * MediaRecorder, so it only needs Base64.
 */
object MediaUtils {
    /** Longest-side cap in px after downscaling. */
    const val MAX_IMAGE_DIM = 1280
    /** Hard cap on compressed JPEG bytes before Base64 (~150 KB). */
    const val MAX_IMAGE_BYTES = 150 * 1024
    /** Cap on voice-note length. Most Nostr relays hard-cap event size between 64-100 KB,
     *  and each fan-out to a circle member ships its own NIP-44 encrypted copy. 10 s at 24 kbps
     *  encodes to ~35 KB encrypted (~47 KB after Base64 + NIP-44 overhead), keeping the relay
     *  happy on 5-member circles even on the tighter relays. Recording auto-stops here. */
    const val MAX_AUDIO_MS = 10 * 1000L

    /** Hard cap on a raw file attachment before Base64 (~100 KB). Unlike images, files can't be
     *  transcoded down, so anything larger is rejected outright rather than shrunk. 100 KB raw
     *  encodes to ~133 KB Base64, staying below the image payload budget while still fitting a
     *  per-recipient circle fan-out on the tighter relays. */
    const val MAX_FILE_BYTES = 100 * 1024

    /** Result of reading a picked file: the bytes, or a distinct reason so the UI can show the
     *  right message ("too large" vs "couldn't read"). */
    sealed interface FileReadResult {
        data class Ok(val bytes: ByteArray, val name: String, val mimeType: String) : FileReadResult
        object TooLarge : FileReadResult
        object Error : FileReadResult
    }

    /**
     * Reads [uri] into memory only if it is at most [MAX_FILE_BYTES], resolving a display name and
     * MIME type along the way. Reads at most one byte past the cap so an accidentally-picked huge
     * file is rejected without being fully loaded. Call off the main thread.
     */
    fun readFileCapped(context: Context, uri: Uri): FileReadResult {
        val name = displayName(context, uri)
        val mime = mimeType(context, uri, name)
        val stream = try {
            context.contentResolver.openInputStream(uri)
        } catch (e: Exception) {
            return FileReadResult.Error
        } ?: return FileReadResult.Error
        return stream.use { input ->
            try {
                val buffer = ByteArrayOutputStream()
                val chunk = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val read = input.read(chunk)
                    if (read == -1) break
                    total += read
                    // One byte over the cap is enough to know it won't fit; stop before buffering more.
                    if (total > MAX_FILE_BYTES) return@use FileReadResult.TooLarge
                    buffer.write(chunk, 0, read)
                }
                FileReadResult.Ok(buffer.toByteArray(), name, mime)
            } catch (e: Exception) {
                FileReadResult.Error
            }
        }
    }

    /** Best-effort display name for a content Uri, falling back to a generic label. */
    private fun displayName(context: Context, uri: Uri): String {
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) {
                        val n = c.getString(idx)
                        if (!n.isNullOrBlank()) return n
                    }
                }
            }
        } catch (e: Exception) {
            // fall through to the Uri-path / generic fallback
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "file"
    }

    /** Resolves a MIME type from the resolver, or the filename extension, defaulting to octet-stream. */
    private fun mimeType(context: Context, uri: Uri, name: String): String {
        context.contentResolver.getType(uri)?.let { if (it.isNotBlank()) return it }
        val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
        if (ext.isNotEmpty()) {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.let { return it }
        }
        return "application/octet-stream"
    }

    /** Approximate decoded byte length of a NO_WRAP Base64 string without allocating the bytes -
     *  good enough for a size label on a file bubble. */
    fun approxDecodedSize(base64: String): Int {
        val padding = when {
            base64.endsWith("==") -> 2
            base64.endsWith("=") -> 1
            else -> 0
        }
        return (base64.length / 4 * 3 - padding).coerceAtLeast(0)
    }

    /** Compact human-readable size (e.g. "12 KB", "980 B") for a file bubble. */
    fun formatSize(bytes: Int): String = when {
        bytes >= 1024 -> "${(bytes + 512) / 1024} KB"
        else -> "$bytes B"
    }

    /**
     * Loads [uri], downscales the longest side to [MAX_IMAGE_DIM], and JPEG-compresses, lowering
     * quality until the result is under [MAX_IMAGE_BYTES]. Returns Base64 (NO_WRAP), or null if the
     * image can't be read. Call off the main thread.
     */
    fun compressImageToBase64(context: Context, uri: Uri): String? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        } ?: return null
        val srcW = bounds.outWidth
        val srcH = bounds.outHeight
        if (srcW <= 0 || srcH <= 0) return null

        // Cheap power-of-two downsample during decode to avoid loading a huge bitmap into memory.
        var sample = 1
        while (srcW / sample > MAX_IMAGE_DIM * 2 || srcH / sample > MAX_IMAGE_DIM * 2) sample *= 2
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, decodeOpts)
        } ?: return null

        val longest = maxOf(bmp.width, bmp.height)
        if (longest > MAX_IMAGE_DIM) {
            val scale = MAX_IMAGE_DIM.toFloat() / longest
            val scaled = bmp.scale(
                (bmp.width * scale).toInt().coerceAtLeast(1),
                (bmp.height * scale).toInt().coerceAtLeast(1),
                true
            )
            if (scaled !== bmp) bmp.recycle()
            bmp = scaled
        }

        var quality = 70
        var bytes: ByteArray
        while (true) {
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
            bytes = out.toByteArray()
            if (bytes.size <= MAX_IMAGE_BYTES || quality <= 30) break
            quality -= 10
        }
        bmp.recycle()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    fun decodeBase64ToBitmap(data: String): Bitmap? = try {
        val bytes = Base64.decode(data, Base64.NO_WRAP)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    } catch (e: Exception) {
        null
    }

    fun encodeBase64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    fun decodeBase64(data: String): ByteArray? = try {
        Base64.decode(data, Base64.NO_WRAP)
    } catch (e: Exception) {
        null
    }
}
