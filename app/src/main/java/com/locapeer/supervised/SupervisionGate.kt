package com.locapeer.supervised

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupervisionGate(
    unlockState: SupervisedModeManager.UnlockState,
    onRequestAccess: () -> Unit,
    onReset: () -> Unit,
    onNavigateBack: (() -> Unit)? = null,
    onUnlocked: () -> Unit
) {
    // Reset singleton unlock state when this screen leaves composition so a subsequent
    // supervised screen cannot inherit the Approved state and bypass the gate.
    DisposableEffect(Unit) {
        onDispose { onReset() }
    }

    LaunchedEffect(unlockState) {
        if (unlockState is SupervisedModeManager.UnlockState.Approved) onUnlocked()
    }

    Scaffold(
        topBar = {
            if (onNavigateBack != null) {
                TopAppBar(
                    title = {},
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                Icons.Default.Lock,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "Device is Supervised",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Supervisor approval is required to access settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(32.dp))

            when (unlockState) {
                is SupervisedModeManager.UnlockState.Idle -> {
                    Button(
                        onClick = onRequestAccess,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Request Access") }
                }
                is SupervisedModeManager.UnlockState.Requesting -> {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Waiting for supervisor approval…",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(16.dp))
                    TextButton(onClick = onReset) { Text("Cancel") }
                }
                is SupervisedModeManager.UnlockState.Denied -> {
                    Text(
                        "Access denied by supervisor.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onReset, modifier = Modifier.fillMaxWidth()) { Text("Try Again") }
                }
                is SupervisedModeManager.UnlockState.TimedOut -> {
                    Text(
                        "Request timed out. Supervisor did not respond.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onReset, modifier = Modifier.fillMaxWidth()) { Text("Try Again") }
                }
                is SupervisedModeManager.UnlockState.Approved -> CircularProgressIndicator()
            }
        }
    }
}
