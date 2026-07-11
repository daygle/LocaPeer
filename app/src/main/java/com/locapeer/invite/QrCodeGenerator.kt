package com.locapeer.invite

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.qrcode.QRCodeWriter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class QrCodeGenerator @Inject constructor() {

    fun generate(content: String, size: Int = 512): Bitmap? {
        return try {
            val hints = mapOf(EncodeHintType.MARGIN to 1)
            val bits = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
            val bitmap = createBitmap(size, size, Bitmap.Config.RGB_565)
            for (x in 0 until size) {
                for (y in 0 until size) {
                    bitmap[x, y] = if (bits[x, y]) Color.BLACK else Color.WHITE
                }
            }
            bitmap
        } catch (e: WriterException) {
            null
        }
    }
}
