package com.locapeer.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.locapeer.R
import kotlin.math.roundToInt

/** Retention durations offered by the selector, in days (0 = keep forever). */
val RETENTION_OPTIONS = listOf(0, 1, 3, 7, 14, 30, 90)

/** Localized label for a retention duration in days. */
@Composable
private fun retentionLabel(days: Int): String =
    if (days == 0) stringResource(R.string.retention_forever)
    else pluralStringResource(R.plurals.retention_days, days, days)

@Composable
fun RetentionRow(
    title: String,
    subtitle: String,
    selected: Int,
    onSelected: (Int) -> Unit,
    icon: ImageVector? = null,
    purgeLabel: String? = null,
    onPurge: (() -> Unit)? = null
) {
    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
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
    val initialIndex = RETENTION_OPTIONS.indexOfFirst { it == selected }.coerceAtLeast(0)
    var sliderValue by remember(selected) { mutableFloatStateOf(initialIndex.toFloat()) }
    val currentIndex = sliderValue.roundToInt().coerceIn(0, RETENTION_OPTIONS.size - 1)

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = stringResource(R.string.retention_keep_for, retentionLabel(RETENTION_OPTIONS[currentIndex])),
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
                onSelected(RETENTION_OPTIONS[finalIndex])
            },
            valueRange = 0f..(RETENTION_OPTIONS.size - 1).toFloat(),
            steps = RETENTION_OPTIONS.size - 2,
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(stringResource(R.string.retention_forever), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(pluralStringResource(R.plurals.retention_days, 90, 90), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
