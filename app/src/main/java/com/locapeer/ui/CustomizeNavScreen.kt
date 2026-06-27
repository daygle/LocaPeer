package com.locapeer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.locapeer.settings.AppPreferences
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomizeNavScreen(
    prefs: AppPreferences,
    onNavigateBack: () -> Unit
) {
    val settings by prefs.settings.collectAsState(initial = null)
    val scope = rememberCoroutineScope()

    // Build ordered list with visible flag. Map is always visible and pinned first.
    val currentIds = settings?.navTabIds ?: listOf("map", "messages", "contacts", "invite", "settings")
    val screenByRoute = ALL_NAV_SCREENS.associateBy { it.route }

    // State: ordered list of (Screen, visible)
    val items = remember(currentIds) {
        val visibleSet = currentIds.toSet()
        // Start with the user's saved order for visible items
        val orderedVisible = currentIds.mapNotNull { screenByRoute[it] }
        // Append any screens not currently in the list as hidden
        val hidden = ALL_NAV_SCREENS.filter { it.route !in visibleSet }
        (orderedVisible + hidden).map { it to (it.route in visibleSet) }.toMutableStateList()
    }

    val visibleCount = items.count { it.second }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Customize Navigation") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Text(
                "Choose which tabs appear in the bottom bar and their order. Map is always included.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )

            LazyColumn(modifier = Modifier.weight(1f)) {
                itemsIndexed(items, key = { _, item -> item.first.route }) { index, (screen, visible) ->
                    val isMap = screen.route == Screen.Map.route
                    // Disable unchecking if it's the last visible non-Map item (need ≥2 total)
                    val canToggle = !isMap && !(visible && visibleCount <= 2)

                    ListItem(
                        headlineContent = {
                            Text(
                                screen.label,
                                fontWeight = if (isMap) FontWeight.SemiBold else FontWeight.Normal
                            )
                        },
                        leadingContent = {
                            Icon(screen.icon, contentDescription = null)
                        },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // Up/down only for non-Map visible items
                                if (!isMap) {
                                    IconButton(
                                        onClick = {
                                            if (index > 0) {
                                                val tmp = items[index]
                                                items[index] = items[index - 1]
                                                items[index - 1] = tmp
                                                persist(items, prefs, scope)
                                            }
                                        },
                                        enabled = index > 0
                                    ) {
                                        Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move up", modifier = Modifier.size(20.dp))
                                    }
                                    IconButton(
                                        onClick = {
                                            if (index < items.size - 1) {
                                                val tmp = items[index]
                                                items[index] = items[index + 1]
                                                items[index + 1] = tmp
                                                persist(items, prefs, scope)
                                            }
                                        },
                                        enabled = index < items.size - 1
                                    ) {
                                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move down", modifier = Modifier.size(20.dp))
                                    }
                                } else {
                                    // Placeholder width so Map row aligns with others
                                    Spacer(Modifier.width(96.dp))
                                }

                                Switch(
                                    checked = visible || isMap,
                                    onCheckedChange = { checked ->
                                        if (canToggle) {
                                            items[index] = screen to checked
                                            persist(items, prefs, scope)
                                        }
                                    },
                                    enabled = canToggle || isMap
                                )
                            }
                        }
                    )
                    HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                }
            }
        }
    }
}

private fun persist(
    items: List<Pair<Screen, Boolean>>,
    prefs: AppPreferences,
    scope: kotlinx.coroutines.CoroutineScope
) {
    val ids = items.filter { it.second }.map { it.first.route }
    scope.launch { prefs.setNavTabIds(ids) }
}
