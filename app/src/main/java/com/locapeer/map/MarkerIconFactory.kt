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
    "#E53935", "#F57C00", "#F9A825", "#388E3C",
    "#00897B", "#0097A7", "#1976D2", "#303F9F",
    "#7B1FA2", "#C2185B", "#5D4037", "#455A64"
)

object MarkerIconFactory {

    private val cache = LruCache<String, BitmapDrawable>(64)

    /** Creates a colored circle with initials as an OSMDroid marker icon. */
    fun create(context: Context, displayName: String, isOverdue: Boolean, isSos: Boolean, pinColor: String = ""): BitmapDrawable {
        val key = "$displayName-$isOverdue-$isSos-$pinColor"
        cache.get(key)?.let { return it }

        val sizePx = (context.resources.displayMetrics.density * 48).toInt()
        val bitmap = Bitmap.createBitmap(sizePx + 8, sizePx + 16, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val cx = (sizePx + 8) / 2f
        val radius = sizePx / 2f - 2

        // SOS/overdue override first; then user's chosen colour; then deterministic fallback
        val fillColor = when {
            isSos -> "#D32F2F".toColorInt()
            isOverdue -> "#9E9E9E".toColorInt()
            pinColor.isNotEmpty() -> pinColor.toColorInt()
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

    /** Small filled circle for intermediate waypoint markers on the history map. */
    fun createDotIcon(context: Context, pinColor: String, isSos: Boolean = false): BitmapDrawable {
        val key = "dot-$pinColor-$isSos"
        cache.get(key)?.let { return it }

        val dp = context.resources.displayMetrics.density
        val sizePx = (dp * 14).toInt()
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val cx = sizePx / 2f

        val fillColor = when {
            isSos -> "#D32F2F".toColorInt()
            pinColor.isNotEmpty() -> pinColor.toColorInt()
            else -> "#1976D2".toColorInt()
        }

        canvas.drawCircle(cx, cx, cx - dp, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fillColor })
        canvas.drawCircle(cx, cx, cx - dp, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            style = Paint.Style.STROKE
            strokeWidth = dp * 1.5f
        })

        val drawable = BitmapDrawable(context.resources, bitmap)
        cache.put(key, drawable)
        return drawable
    }

    private var myLocationCache: Pair<String, BitmapDrawable>? = null

    /** Creates a pulsing dot for the user's own location, using their chosen pin colour. */
    fun createMyLocationIcon(context: Context, pinColor: String = ""): BitmapDrawable {
        myLocationCache?.takeIf { it.first == pinColor }?.let { return it.second }
        val dp = context.resources.displayMetrics.density
        val sizePx = (dp * 48).toInt()
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val cx = sizePx / 2f

        val dotArgb = if (pinColor.isNotEmpty()) pinColor.toColorInt() else 0xFF4285F4.toInt()
        val pulseArgb = (dotArgb and 0x00FFFFFF) or 0x33000000

        // Outer pulse ring
        canvas.drawCircle(cx, cx, cx - 2, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pulseArgb })

        // Inner dot
        canvas.drawCircle(cx, cx, dp * 8, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = dotArgb })

        // White border
        canvas.drawCircle(cx, cx, dp * 8, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            style = Paint.Style.STROKE
            strokeWidth = dp * 2f
        })

        val drawable = BitmapDrawable(context.resources, bitmap)
        myLocationCache = pinColor to drawable
        return drawable
    }
}
