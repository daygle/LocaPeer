package com.locapeer.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.util.LruCache
import androidx.core.graphics.toColorInt

private val PIN_COLORS = listOf(
    "#1565C0", "#00897B", "#E53935", "#7B1FA2",
    "#F57F17", "#00838F", "#2E7D32", "#C62828"
)

object MarkerIconFactory {

    private val cache = LruCache<String, BitmapDrawable>(64)

    /** Creates a colored circle with initials as an OSMDroid marker icon. */
    fun create(context: Context, displayName: String, isOverdue: Boolean, isSos: Boolean): BitmapDrawable {
        val key = "$displayName-$isOverdue-$isSos"
        cache.get(key)?.let { return it }

        val sizePx = (context.resources.displayMetrics.density * 48).toInt()
        val bitmap = Bitmap.createBitmap(sizePx + 8, sizePx + 16, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val cx = (sizePx + 8) / 2f
        val radius = sizePx / 2f - 2

        // Pick color deterministically from name, override for SOS/overdue
        val fillColor = when {
            isSos -> "#D32F2F".toColorInt()
            isOverdue -> "#9E9E9E".toColorInt()
            else -> PIN_COLORS[Math.abs(displayName.hashCode()) % PIN_COLORS.size].toColorInt()
        }

        // Shadow
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x33000000
            this.setShadowLayer(6f, 0f, 3f, 0x55000000)
        }
        canvas.drawCircle(cx, cx + 2, radius, shadowPaint)

        // Circle fill
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fillColor }
        canvas.drawCircle(cx, cx, radius, fillPaint)

        // White border
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            style = Paint.Style.STROKE
            strokeWidth = (context.resources.displayMetrics.density * 2.5f)
        }
        canvas.drawCircle(cx, cx, radius - 1, borderPaint)

        // Initials
        val initial = displayName.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textSize = sizePx * 0.42f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val bounds = Rect()
        textPaint.getTextBounds(initial, 0, initial.length, bounds)
        canvas.drawText(initial, cx, cx - bounds.exactCenterY(), textPaint)

        // Tail (downward triangle to anchor the pin)
        val tailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fillColor }
        val path = android.graphics.Path().apply {
            moveTo(cx - 6, cx + radius - 2)
            lineTo(cx + 6, cx + radius - 2)
            lineTo(cx, cx + radius + 12)
            close()
        }
        canvas.drawPath(path, tailPaint)

        val drawable = BitmapDrawable(context.resources, bitmap)
        cache.put(key, drawable)
        return drawable
    }

    private var myLocationCache: BitmapDrawable? = null

    /** Creates a blue pulsing-style dot for the user's own location. */
    fun createMyLocationIcon(context: Context): BitmapDrawable {
        myLocationCache?.let { return it }
        val dp = context.resources.displayMetrics.density
        val sizePx = (dp * 48).toInt()
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val cx = sizePx / 2f

        // Outer pulse ring
        val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x334285F4.toInt()
        }
        canvas.drawCircle(cx, cx, cx - 2, pulsePaint)

        // Inner blue dot
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF4285F4.toInt()
        }
        canvas.drawCircle(cx, cx, dp * 8, dotPaint)

        // White border on dot
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            style = Paint.Style.STROKE
            strokeWidth = dp * 2f
        }
        canvas.drawCircle(cx, cx, dp * 8, borderPaint)

        val drawable = BitmapDrawable(context.resources, bitmap)
        myLocationCache = drawable
        return drawable
    }
}
