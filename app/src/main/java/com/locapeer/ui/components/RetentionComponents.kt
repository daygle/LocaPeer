package com.locapeer.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

val RETENTION_OPTIONS = listOf(
    0 to "Forever",
    1 to "1 day",
    3 to "3 days",
    7 to "7 days",
    14 to "14 days",
    30 to "30 days",
    90 to "90 days"
)

@Composable
fun RetentionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    selected: Int,
    onSelected: (Int) -> Unit,
    purgeLabel: String? = null,
    onPurge: (() -> Unit)? = null
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(10.dp))
        RetentionSelector(selected = selected, onSelected = onSelected)
        if (onPurge != null && purgeLabel != null) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onPurge, modifier = Modifier.fillMaxWidth()) {
                Text(purgeLabel)
            }
        }
    }
}

@Composable
fun RetentionSelector(selected: Int, onSelected: (Int) -> Unit) {
    val initialIndex = RETENTION_OPTIONS.indexOfFirst { it.first == selected }.coerceAtLeast(0)
    var sliderValue by remember(selected) { mutableFloatStateOf(initialIndex.toFloat()) }
    val currentIndex = sliderValue.roundToInt().coerceIn(0, RETENTION_OPTIONS.size - 1)

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Keep for: ${RETENTION_OPTIONS[currentIndex].second}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
        }
        Slider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            onValueChangeFinished = {
                val finalIndex = sliderValue.roundToInt().coerceIn(0, RETENTION_OPTIONS.size - 1)
                onSelected(RETENTION_OPTIONS[finalIndex].first)
            },
            valueRange = 0f..(RETENTION_OPTIONS.size - 1).toFloat(),
            steps = RETENTION_OPTIONS.size - 2,
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Forever", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("90 days", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
