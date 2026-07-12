package com.locapeer.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.locapeer.R

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun OnboardingScreen(
    vm: OnboardingViewModel,
    onComplete: () -> Unit
) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current

    val basicPermissionsState = rememberMultiplePermissionsState(
        permissions = PermissionManager.REQUIRED_PERMISSIONS + PermissionManager.OPTIONAL_PERMISSIONS
    )

    val backgroundLocationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            vm.nextStep()
        } else {
            vm.setPermissionDenied(true)
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(top = 32.dp, bottom = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (state.step) {
                OnboardingStep.IDENTITY -> IdentityStep(state, vm)
                OnboardingStep.BACKUP -> BackupStep(state, vm)
                OnboardingStep.PERMISSIONS -> PermissionsStep(basicPermissionsState) { vm.nextStep() }
                OnboardingStep.BACKGROUND_LOCATION -> BackgroundLocationStep(
                    showError = state.showPermissionDeniedError
                ) {
                    vm.setPermissionDenied(false)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    } else {
                        vm.nextStep()
                    }
                }
                OnboardingStep.BATTERY -> BatteryStep {
                    PermissionManager.requestBatteryOptimizationExemption(context)
                    vm.nextStep()
                }
                OnboardingStep.DONE -> DoneStep { vm.complete(onComplete) }
            }
        }
    }
}

@Composable
private fun IdentityStep(state: OnboardingState, vm: OnboardingViewModel) {
    var showImportDialog by remember { mutableStateOf(false) }
    var importInput by remember { mutableStateOf("") }

    // Close the dialog once import succeeds (key updated, no error, not loading)
    LaunchedEffect(state.publicKeyHex, state.importError, state.isLoading) {
        if (showImportDialog && !state.isLoading && state.importError == null && state.publicKeyHex.isNotEmpty()) {
            showImportDialog = false
        }
    }

    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false; vm.clearImportError() },
            icon = { Icon(Icons.Default.VpnKey, contentDescription = null) },
            title = { Text(stringResource(R.string.onboarding_restore_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        stringResource(R.string.onboarding_restore_message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = importInput,
                        onValueChange = { importInput = it; vm.clearImportError() },
                        label = { Text(stringResource(R.string.onboarding_private_key_hex)) },
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                        isError = state.importError != null,
                        supportingText = state.importError?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { vm.importPrivateKey(importInput) },
                    enabled = importInput.isNotBlank() && !state.isLoading
                ) { Text(stringResource(R.string.common_restore)) }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false; vm.clearImportError() }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    Box(
        modifier = Modifier
            .size(100.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = Color.Unspecified
        )
    }

    Spacer(Modifier.height(32.dp))

    Text(
        stringResource(R.string.app_name),
        style = MaterialTheme.typography.displayMedium,
        color = MaterialTheme.colorScheme.primary
    )

    Spacer(Modifier.height(12.dp))

    Text(
        stringResource(R.string.onboarding_tagline),
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(Modifier.height(48.dp))

    FeatureRow(
        icon = Icons.Default.Lock,
        title = stringResource(R.string.onboarding_feature_e2e_title),
        subtitle = stringResource(R.string.onboarding_feature_e2e_subtitle)
    )
    Spacer(Modifier.height(20.dp))
    FeatureRow(
        icon = Icons.Default.CloudOff,
        title = stringResource(R.string.onboarding_feature_servers_title),
        subtitle = stringResource(R.string.onboarding_feature_servers_subtitle)
    )
    Spacer(Modifier.height(20.dp))
    FeatureRow(
        icon = Icons.Default.PhoneAndroid,
        title = stringResource(R.string.onboarding_feature_phone_title),
        subtitle = stringResource(R.string.onboarding_feature_phone_subtitle)
    )

    Spacer(Modifier.height(48.dp))

    Text(
        stringResource(R.string.onboarding_name_prompt),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.fillMaxWidth()
    )

    Spacer(Modifier.height(16.dp))

    OutlinedTextField(
        value = state.displayName,
        onValueChange = vm::setDisplayName,
        label = { Text(stringResource(R.string.settings_display_name_label)) },
        placeholder = { Text(stringResource(R.string.onboarding_name_placeholder)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Words,
            imeAction = ImeAction.Done
        ),
        keyboardActions = KeyboardActions(
            onDone = {
                if (state.displayName.isNotBlank()) vm.nextStep()
            }
        )
    )

    Spacer(Modifier.height(32.dp))

    Button(
        onClick = { vm.nextStep() },
        enabled = state.displayName.isNotBlank() && !state.isLoading,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Text(stringResource(R.string.onboarding_get_started), style = MaterialTheme.typography.titleMedium)
    }

    Spacer(Modifier.height(12.dp))

    TextButton(
        onClick = { showImportDialog = true },
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(Icons.Default.VpnKey, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.onboarding_restore_from_backup))
    }

    Spacer(Modifier.height(16.dp))

    Text(
        stringResource(R.string.onboarding_key_note),
        style = MaterialTheme.typography.labelSmall,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.outline
    )
}

/**
 * Proactive "back up your identity" step for a freshly generated key (skipped for users who
 * restored an existing key). Reveals the private key on demand — the same value Settings → View
 * Private Key shows, under the app-wide FLAG_SECURE — so the user can copy and store it before
 * they have anything to lose. Non-blocking: "back up later" always proceeds.
 */
@Composable
private fun BackupStep(state: OnboardingState, vm: OnboardingViewModel) {
    val context = LocalContext.current
    // Resolve at composition time: lint forbids Context.getString() from inside a click lambda
    // because a config change wouldn't invalidate it.
    val copiedMessage = stringResource(R.string.onboarding_backup_copied)
    var confirmed by remember { mutableStateOf(false) }
    val key = state.privateKeyHex

    StepHeader(
        icon = Icons.Default.VpnKey,
        title = stringResource(R.string.onboarding_backup_title),
        description = stringResource(R.string.onboarding_backup_desc)
    )

    Spacer(Modifier.height(24.dp))

    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Text(
                stringResource(R.string.onboarding_backup_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }

    Spacer(Modifier.height(24.dp))

    if (key.isEmpty()) {
        Button(
            onClick = { vm.revealPrivateKey() },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Icon(Icons.Default.VpnKey, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.onboarding_backup_reveal))
        }
    } else {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                key,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.padding(16.dp)
            )
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = {
                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("LocaPeer private key", key))
                android.widget.Toast.makeText(
                    context, copiedMessage, android.widget.Toast.LENGTH_SHORT
                ).show()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.onboarding_backup_copy))
        }
        Spacer(Modifier.height(16.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable { confirmed = !confirmed }
        ) {
            Checkbox(checked = confirmed, onCheckedChange = { confirmed = it })
            Text(stringResource(R.string.onboarding_backup_confirm), style = MaterialTheme.typography.bodyMedium)
        }
    }

    Spacer(Modifier.height(24.dp))

    Button(
        onClick = { vm.nextStep() },
        enabled = confirmed,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Text(stringResource(R.string.onboarding_backup_continue), style = MaterialTheme.typography.titleMedium)
    }

    Spacer(Modifier.height(8.dp))

    TextButton(onClick = { vm.nextStep() }, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.onboarding_backup_later))
    }

    Spacer(Modifier.height(12.dp))

    Text(
        stringResource(R.string.onboarding_backup_note),
        style = MaterialTheme.typography.labelSmall,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.outline
    )
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun PermissionsStep(
    permissionsState: com.google.accompanist.permissions.MultiplePermissionsState,
    onNext: () -> Unit
) {
    StepHeader(
        icon = Icons.AutoMirrored.Filled.FactCheck,
        title = stringResource(R.string.onboarding_permissions_title),
        description = stringResource(R.string.onboarding_permissions_desc)
    )

    Spacer(Modifier.height(48.dp))

    // Gate advancing on the required permissions only. The optional ones (e.g.
    // Activity Recognition) are requested in the same prompt but their denial must
    // not block onboarding - tracking still works without them.
    val requiredGranted = permissionsState.permissions
        .filter { it.permission in PermissionManager.REQUIRED_PERMISSIONS }
        .all { it.status.isGranted }

    // Explain what's about to be asked - especially the optional physical-activity
    // access - before the system dialogs appear, so the prompts have context.
    var showRationale by remember { mutableStateOf(false) }
    if (showRationale) {
        AlertDialog(
            onDismissRequest = { showRationale = false },
            icon = { Icon(Icons.AutoMirrored.Filled.FactCheck, contentDescription = null) },
            title = { Text(stringResource(R.string.onboarding_before_ask_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.onboarding_before_ask_intro))
                    Text(stringResource(R.string.onboarding_before_ask_location))
                    Text(stringResource(R.string.onboarding_before_ask_camera))
                    Text(stringResource(R.string.onboarding_before_ask_notifications))
                    Text(stringResource(R.string.onboarding_before_ask_activity))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showRationale = false
                    permissionsState.launchMultiplePermissionRequest()
                }) { Text(stringResource(R.string.common_continue)) }
            },
            dismissButton = {
                TextButton(onClick = { showRationale = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    if (requiredGranted) {
        LaunchedEffect(Unit) { onNext() }
    } else {
        Button(
            onClick = { showRationale = true },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text(stringResource(R.string.onboarding_grant_permissions), style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun BackgroundLocationStep(showError: Boolean, onNext: () -> Unit) {
    StepHeader(
        icon = Icons.Default.LocationOn,
        title = stringResource(R.string.onboarding_bg_location_title),
        description = stringResource(R.string.onboarding_bg_location_desc)
    )

    if (showError) {
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.onboarding_bg_location_error),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center
        )
    }

    Spacer(Modifier.height(48.dp))

    Button(
        onClick = onNext,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Text(stringResource(R.string.onboarding_configure_location), style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun BatteryStep(onNext: () -> Unit) {
    StepHeader(
        icon = Icons.Default.BatteryChargingFull,
        title = stringResource(R.string.onboarding_battery_title),
        description = stringResource(R.string.onboarding_battery_desc)
    )

    Spacer(Modifier.height(48.dp))

    Button(
        onClick = onNext,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Text(stringResource(R.string.onboarding_disable_optimizations), style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun DoneStep(onComplete: () -> Unit) {
    StepHeader(
        icon = Icons.Default.CheckCircle,
        title = stringResource(R.string.onboarding_done_title),
        description = stringResource(R.string.onboarding_done_desc)
    )

    Spacer(Modifier.height(48.dp))

    Button(
        onClick = onComplete,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Text(stringResource(R.string.onboarding_start_using), style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun StepHeader(icon: ImageVector, title: String, description: String) {
    Box(
        modifier = Modifier
            .size(80.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, Modifier.size(40.dp), MaterialTheme.colorScheme.onSecondaryContainer)
    }
    Spacer(Modifier.height(24.dp))
    Text(title, style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(16.dp))
    Text(
        description,
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun FeatureRow(icon: ImageVector, title: String, subtitle: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, Modifier.size(24.dp), MaterialTheme.colorScheme.primary)
        }
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
