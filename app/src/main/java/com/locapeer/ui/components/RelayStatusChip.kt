package com.locapeer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun RelayStatusChip(
    relayStatus: Map<String, Boolean>,
    modifier: Modifier = Modifier
) {
    val allConnected = relayStatus.isNotEmpty() && relayStatus.values.all { it }
    val anyConnected = relayStatus.values.any { it }

    val dotColor = when {
        relayStatus.isEmpty() -> Color(0xFFFFB300)
        allConnected -> Color(0xFF4CAF50)
        anyConnected -> Color(0xFFFFB300)
        else -> MaterialTheme.colorScheme.error
    }
    val dotLabel = when {
        relayStatus.isEmpty() -> "Connecting…"
        allConnected -> "Relays connected"
        anyConnected -> "Partial connection"
        else -> "Offline"
    }

    Surface(
        modifier = modifier.shadow(2.dp, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Text(dotLabel, style = MaterialTheme.typography.labelSmall)
        }
    }
}
