package com.locapeer.ui.theme

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable

/**
 * Shared colors for the app's top headings. Uses a subtly elevated grey
 * (surfaceContainer) so the heading reads as a distinct band above the page
 * content, matching the look of Google's Material 3 apps. Adapts automatically
 * to light and dark themes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun locaPeerTopAppBarColors(): TopAppBarColors = TopAppBarDefaults.topAppBarColors(
    containerColor = MaterialTheme.colorScheme.surfaceContainer,
    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
)
