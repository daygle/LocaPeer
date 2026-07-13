package com.locapeer.supervised

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import com.locapeer.ui.theme.locaPeerTopAppBarColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.locapeer.R

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
                    colors = locaPeerTopAppBarColors(),
                    title = {},
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
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
                stringResource(R.string.gate_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.gate_message),
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
                    ) { Text(stringResource(R.string.gate_request_access)) }
                }
                is SupervisedModeManager.UnlockState.Requesting -> {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(
                        stringResource(R.string.gate_waiting),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(16.dp))
                    TextButton(onClick = onReset) { Text(stringResource(R.string.common_cancel)) }
                }
                is SupervisedModeManager.UnlockState.Denied -> {
                    Text(
                        stringResource(R.string.gate_denied),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onReset, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.common_try_again)) }
                }
                is SupervisedModeManager.UnlockState.TimedOut -> {
                    Text(
                        stringResource(R.string.gate_timed_out),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onReset, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.common_try_again)) }
                }
                is SupervisedModeManager.UnlockState.Approved -> CircularProgressIndicator()
            }
        }
    }
}
