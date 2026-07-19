package com.locapeer.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.util.LruCache
import androidx.core.graphics.applyCanvas
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.toColorInt

private val PIN_COLORS = listOf(
    "#E53935", "#F57C00", "#F9A825", "#388E3C",
    "#00897B", "#0097A7", "#1976D2", "#303F9F",
    "#7B1FA2", "#C2185B", "#5D4037", "#455A64"
)

object MarkerIconFactory {

    private val cache = LruCache<String, BitmapDrawable>(64)

    /**
     * Creates a colored circle with initials as an OSMDroid marker icon. When
     * [showDirection] is set, a small heading arrow is drawn on the ring pointing along
     * [bearingDegrees] (0 = north, clockwise), so a moving contact's direction is visible
     * at a glance. The arrow fits within enlarged top/side margins and the existing tail
     * region, so the tail tip stays at the bitmap's bottom-centre and the caller's
     * ANCHOR_CENTER / ANCHOR_BOTTOM placement is unchanged.
     */
    fun create(
        context: Context,
        displayName: String,
        isOverdue: Boolean,
        isSos: Boolean,
        pinColor: String = "",
        isSelected: Boolean = false,
        bearingDegrees: Float = 0f,
        showDirection: Boolean = false,
        isLive: Boolean = false
    ): BitmapDrawable {
        val density = context.resources.displayMetrics.density
        // A live contact gets a green ring; suppress it while SOS/overdue own the colour so
        // those states stay unambiguous.
        val showLive = isLive && !isSos && !isOverdue
        // Quantise the bearing in the cache key so continuous heading changes reuse a
        // small, bounded set of bitmaps instead of thrashing the LRU cache.
        val quantBearing = if (showDirection) (Math.round(bearingDegrees / 15f) * 15) % 360 else 0
        val key = "$displayName-$isOverdue-$isSos-$pinColor-$isSelected-$showDirection-$quantBearing-$showLive"
        cache.get(key)?.let { return it }

        // Selected pins render larger so the tapped marker stands out on the history map
        val sizePx = (density * if (isSelected) 60 else 48).toInt()
        val radius = sizePx / 2f - 2f
        // A moving pin gets a heading arrow on the ring, which needs clearance beyond the
        // circle on every side. The bottom clearance is folded into the tail length so the
        // tail tip still lands at the bitmap's bottom-centre and the caller's ANCHOR_BOTTOM
        // keeps pointing the pin at the exact coordinate (no anchor change needed).
        val arrowReach = if (showDirection) density * 9f else 0f
        // The live ring sits just outside the white border and needs a little clearance so
        // it isn't clipped by the bitmap edge.
        val liveReach = if (showLive) density * 4f else 0f
        val sideTopPad = density * 4f + maxOf(arrowReach, liveReach)
        val tailDrop = maxOf(density * 9f, arrowReach + density * 2f)
        val width = (sizePx + sideTopPad * 2f).toInt().coerceAtLeast(1)
        val height = (sideTopPad + radius * 2f + tailDrop + density * 2f).toInt().coerceAtLeast(1)
        val bitmap = createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.applyCanvas {
            val cx = width / 2f
            val cy = sideTopPad + radius

            // SOS/overdue override first; then user's chosen colour; then deterministic fallback
            val fillColor = when {
                isSos -> "#D32F2F".toColorInt()
                isOverdue -> "#9E9E9E".toColorInt()
                pinColor.isNotEmpty() -> pinColor.toColorInt()
                else -> PIN_COLORS[Math.abs(displayName.hashCode()) % PIN_COLORS.size].toColorInt()
            }

            // Heading arrow, drawn first so the circle and its white border cap the base,
            // leaving a clean outward-pointing chevron. bearing 0 = north (screen up), so
            // the direction unit vector is (sin, -cos) and its perpendicular is (-uy, ux).
            if (showDirection) {
                val rad = Math.toRadians(bearingDegrees.toDouble())
                val ux = Math.sin(rad).toFloat()
                val uy = -Math.cos(rad).toFloat()
                val perpX = -uy
                val perpY = ux
                val baseR = radius + density * 1f
                val tipR = radius + arrowReach
                val halfW = density * 5f
                val arrowPath = android.graphics.Path().apply {
                    moveTo(cx + ux * tipR, cy + uy * tipR)
                    lineTo(cx + ux * baseR + perpX * halfW, cy + uy * baseR + perpY * halfW)
                    lineTo(cx + ux * baseR - perpX * halfW, cy + uy * baseR - perpY * halfW)
                    close()
                }
                drawPath(arrowPath, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fillColor })
                drawPath(arrowPath, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0xFFFFFFFF.toInt()
                    style = Paint.Style.STROKE
                    strokeWidth = density * 1.5f
                    strokeJoin = Paint.Join.ROUND
                })
            }

            // Shadow
            val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0x33000000
                this.setShadowLayer(6f, 0f, 3f, 0x55000000)
            }
            drawCircle(cx, cy + 2, radius, shadowPaint)

            // Circle fill
            val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fillColor }
            drawCircle(cx, cy, radius, fillPaint)

            // White border
            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFFFFFF.toInt()
                style = Paint.Style.STROKE
                strokeWidth = density * 2.5f
            }
            drawCircle(cx, cy, radius - 1, borderPaint)

            // Live ring: a green halo just outside the white border marking a contact who is
            // streaming location live right now. Drawn before the tail so the tail caps its
            // lower edge cleanly.
            if (showLive) {
                drawCircle(cx, cy, radius + density * 2.5f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = "#00C853".toColorInt()
                    style = Paint.Style.STROKE
                    strokeWidth = density * 2.5f
                })
            }

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
            drawText(initial, cx, cy - bounds.exactCenterY(), textPaint)

            // Tail (downward triangle to anchor the pin)
            val tailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fillColor }
            val halfBase = density * 4.5f
            val path = android.graphics.Path().apply {
                moveTo(cx - halfBase, cy + radius - 2)
                lineTo(cx + halfBase, cy + radius - 2)
                lineTo(cx, cy + radius + tailDrop)
                close()
            }
            drawPath(path, tailPaint)
        }

        val drawable = bitmap.toDrawable(context.resources)
        cache.put(key, drawable)
        return drawable
    }

    /** Small filled circle for intermediate waypoint markers on the history map. */
    fun createDotIcon(context: Context, pinColor: String, isSos: Boolean = false, isSelected: Boolean = false): BitmapDrawable {
        val key = "dot-$pinColor-$isSos-$isSelected"
        cache.get(key)?.let { return it }

        val dp = context.resources.displayMetrics.density
        val dotPx = (dp * if (isSelected) 20 else 14).toInt()
        val haloPx = if (isSelected) (dp * 7).toInt() else 0
        val sizePx = dotPx + haloPx * 2
        val bitmap = createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        bitmap.applyCanvas {
            val cx = sizePx / 2f
            val radius = dotPx / 2f - dp

            val fillColor = when {
                isSos -> "#D32F2F".toColorInt()
                pinColor.isNotEmpty() -> pinColor.toColorInt()
                else -> "#1976D2".toColorInt()
            }

            if (isSelected) {
                // Translucent halo behind the dot so the tapped pin stands out from its neighbours
                drawCircle(cx, cx, cx, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = (fillColor and 0x00FFFFFF) or 0x55000000
                })
            }

            drawCircle(cx, cx, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fillColor })
            drawCircle(cx, cx, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFFFFFF.toInt()
                style = Paint.Style.STROKE
                strokeWidth = dp * if (isSelected) 2f else 1.5f
            })
        }

        val drawable = bitmap.toDrawable(context.resources)
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
            this.color = 0xFF1A1C1E.toInt()
            textSize = dp * 12
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val subtitleText = "👤 $subtitle"
        val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = 0xFF43474E.toInt()
            textSize = dp * 10
        }

        val titleBounds = Rect().also { titlePaint.getTextBounds(title, 0, title.length, it) }
        val subBounds = Rect().also { subtitlePaint.getTextBounds(subtitleText, 0, subtitleText.length, it) }

        val contentW = maxOf(titlePaint.measureText(title), subtitlePaint.measureText(subtitleText))
        val titleH = titleBounds.height().toFloat()
        val subH = subBounds.height().toFloat()

        val width = (contentW + padH * 2).toInt().coerceAtLeast(1)
        val height = (titleH + subH + gap + padV * 2).toInt().coerceAtLeast(1)
        val bitmap = createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.applyCanvas {
            val rect = android.graphics.RectF(dp, dp, width - dp, height - dp)
            // Opaque white card so it stays legible over the tinted fence fill.
            drawRoundRect(rect, dp * 6, dp * 6, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = 0xF2FFFFFF.toInt()
            })
            drawRoundRect(rect, dp * 6, dp * 6, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                style = Paint.Style.STROKE
                strokeWidth = dp * 1.5f
            })

            val textX = padH
            val titleBaseline = padV - titleBounds.top
            drawText(title, textX, titleBaseline, titlePaint)
            val subBaseline = titleBaseline + titleBounds.bottom + gap - subBounds.top
            drawText(subtitleText, textX, subBaseline, subtitlePaint)
        }

        val drawable = bitmap.toDrawable(context.resources)
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
        val bitmap = createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        bitmap.applyCanvas {
            val cx = sizePx / 2f

            val baseColor = if (isSos) "#D32F2F".toColorInt() else if (pinColor.isNotEmpty()) pinColor.toColorInt() else 0xFF4285F4.toInt()
            val pulseArgb = (baseColor and 0x00FFFFFF) or 0x33000000

            // Outer pulse ring
            drawCircle(cx, cx, cx - 2, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pulseArgb })

            // Inner dot
            drawCircle(cx, cx, dp * 8, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = baseColor })

            // White border
            drawCircle(cx, cx, dp * 8, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFFFFFF.toInt()
                style = Paint.Style.STROKE
                strokeWidth = dp * 2f
            })
        }

        val drawable = bitmap.toDrawable(context.resources)
        myLocationCache = cacheKey to drawable
        return drawable
    }
}
