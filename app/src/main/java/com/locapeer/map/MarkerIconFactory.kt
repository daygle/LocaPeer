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

    /**
     * A small rounded label drawn at a geofence centre showing its name and the contact it
     * tracks, so it's clear on the map which user each geofence relates to.
     */
    fun createGeofenceLabel(context: Context, title: String, subtitle: String, color: Int): BitmapDrawable {
        val key = "gflabel-$title-$subtitle-$color"
        cache.get(key)?.let { return it }

        val dp = context.resources.displayMetrics.density
        val padH = dp * 8
        val padV = dp * 5
        val gap = dp * 2

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF1A1C1E.toInt()
            textSize = dp * 12
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val subtitleText = "👤 $subtitle"
        val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF43474E.toInt()
            textSize = dp * 10
        }

        val titleBounds = Rect().also { titlePaint.getTextBounds(title, 0, title.length, it) }
        val subBounds = Rect().also { subtitlePaint.getTextBounds(subtitleText, 0, subtitleText.length, it) }

        val contentW = maxOf(titlePaint.measureText(title), subtitlePaint.measureText(subtitleText))
        val titleH = titleBounds.height().toFloat()
        val subH = subBounds.height().toFloat()

        val width = (contentW + padH * 2).toInt().coerceAtLeast(1)
        val height = (titleH + subH + gap + padV * 2).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val rect = android.graphics.RectF(dp, dp, width - dp, height - dp)
        // Opaque white card so it stays legible over the tinted fence fill.
        canvas.drawRoundRect(rect, dp * 6, dp * 6, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = 0xF2FFFFFF.toInt()
        })
        canvas.drawRoundRect(rect, dp * 6, dp * 6, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = dp * 1.5f
        })

        val textX = padH
        val titleBaseline = padV - titleBounds.top
        canvas.drawText(title, textX, titleBaseline, titlePaint)
        val subBaseline = titleBaseline + titleBounds.bottom + gap - subBounds.top
        canvas.drawText(subtitleText, textX, subBaseline, subtitlePaint)

        val drawable = BitmapDrawable(context.resources, bitmap)
        cache.put(key, drawable)
        return drawable
    }

    private var myLocationCache: Pair<String, BitmapDrawable>? = null

    /** Creates a pulsing dot for the user's own location, using their chosen pin colour. */
    fun createMyLocationIcon(context: Context, pinColor: String = "", isSos: Boolean = false): BitmapDrawable {
        val cacheKey = "$pinColor-$isSos"
        myLocationCache?.takeIf { it.first == cacheKey }?.let { return it.second }
        val dp = context.resources.displayMetrics.density
        val sizePx = (dp * 48).toInt()
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val cx = sizePx / 2f

        val baseColor = if (isSos) "#D32F2F".toColorInt() else if (pinColor.isNotEmpty()) pinColor.toColorInt() else 0xFF4285F4.toInt()
        val pulseArgb = (baseColor and 0x00FFFFFF) or 0x33000000

        // Outer pulse ring
        canvas.drawCircle(cx, cx, cx - 2, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pulseArgb })

        // Inner dot
        canvas.drawCircle(cx, cx, dp * 8, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = baseColor })

        // White border
        canvas.drawCircle(cx, cx, dp * 8, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            style = Paint.Style.STROKE
            strokeWidth = dp * 2f
        })

        val drawable = BitmapDrawable(context.resources, bitmap)
        myLocationCache = cacheKey to drawable
        return drawable
    }
}
