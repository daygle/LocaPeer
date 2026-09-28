package com.locapeer.invite

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.createBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.qrcode.QRCodeWriter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class QrCodeGenerator @Inject constructor() {

    /** Renders [content] as a QR bitmap. CPU-bound: call off the main thread. */
    fun generate(content: String, size: Int = 512): Bitmap? {
        return try {
            val hints = mapOf(EncodeHintType.MARGIN to 1)
            val bits = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
            // Fill a pixel buffer and upload it once; a per-pixel Bitmap write is a JNI call
            // for each of the size*size pixels.
            val pixels = IntArray(size * size) { i -> if (bits[i % size, i / size]) Color.BLACK else Color.WHITE }
            createBitmap(size, size, Bitmap.Config.RGB_565).apply {
                setPixels(pixels, 0, size, 0, 0, size, size)
            }
        } catch (e: WriterException) {
            null
        }
    }
}
