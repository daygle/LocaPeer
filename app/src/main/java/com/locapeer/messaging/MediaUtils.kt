package com.locapeer.messaging

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream

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
    /** Cap on voice-note length. Keeps the AAC payload (~24 kbps) small enough that its Base64
     *  form stays within typical relay event-size limits. Recording auto-stops at this point. */
    const val MAX_AUDIO_MS = 60 * 1000L

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
            val scaled = Bitmap.createScaledBitmap(
                bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true
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
