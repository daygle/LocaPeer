package com.locapeer.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Colours the user can pick for their own chat bubbles (Settings → Appearance).
 * [DEFAULT] keeps the theme's primary container; the rest are fixed dark hues that
 * stay legible with white text in both light and dark themes.
 */
enum class BubbleColor(val id: String) {
    DEFAULT("DEFAULT"),
    BLUE("BLUE"),
    GREEN("GREEN"),
    PURPLE("PURPLE"),
    ORANGE("ORANGE"),
    PINK("PINK");

    companion object {
        /** Resolve a stored preference id, falling back to [DEFAULT] for unknown values. */
        fun fromId(id: String?): BubbleColor = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

private val CUSTOM_BUBBLE_BACKGROUNDS = mapOf(
    BubbleColor.BLUE to Color(0xFF1D4ED8),
    BubbleColor.GREEN to Color(0xFF15803D),
    BubbleColor.PURPLE to Color(0xFF6D28D9),
    BubbleColor.ORANGE to Color(0xFFC2410C),
    BubbleColor.PINK to Color(0xFFBE185D),
)

/** Background for a bubble: incoming bubbles always follow the theme. */
@Composable
fun bubbleBackground(isMine: Boolean, selected: BubbleColor): Color = when {
    !isMine -> MaterialTheme.colorScheme.surfaceVariant
    selected == BubbleColor.DEFAULT -> MaterialTheme.colorScheme.primaryContainer
    else -> CUSTOM_BUBBLE_BACKGROUNDS.getValue(selected)
}

/** Text/icon colour that stays readable on [bubbleBackground]. */
@Composable
fun bubbleContent(isMine: Boolean, selected: BubbleColor): Color = when {
    !isMine -> MaterialTheme.colorScheme.onSurfaceVariant
    selected == BubbleColor.DEFAULT -> MaterialTheme.colorScheme.onPrimaryContainer
    else -> Color.White
}
