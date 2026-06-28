package com.locapeer.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

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
    val rows = RETENTION_OPTIONS.chunked(4)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEach { rowItems ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                rowItems.forEach { (days, label) ->
                    FilterChip(
                        selected = selected == days,
                        onClick = { onSelected(days) },
                        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.weight(1f)
                    )
                }
                repeat(4 - rowItems.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
