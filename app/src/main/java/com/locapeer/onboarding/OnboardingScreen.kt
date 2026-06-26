package com.locapeer.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun OnboardingScreen(
    vm: OnboardingViewModel,
    onComplete: () -> Unit
) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current

    val basicPermissionsState = rememberMultiplePermissionsState(
        permissions = PermissionManager.REQUIRED_PERMISSIONS
    )

    val backgroundLocationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) vm.nextStep()
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(top = 72.dp, bottom = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (state.step) {
                OnboardingStep.IDENTITY -> IdentityStep(state, vm)
                OnboardingStep.PERMISSIONS -> PermissionsStep(basicPermissionsState) { vm.nextStep() }
                OnboardingStep.BACKGROUND_LOCATION -> BackgroundLocationStep {
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
    Box(
        modifier = Modifier
            .size(100.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Default.MyLocation,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }

    Spacer(Modifier.height(32.dp))

    Text(
        "LocaPeer",
        style = MaterialTheme.typography.displayMedium,
        color = MaterialTheme.colorScheme.primary
    )

    Spacer(Modifier.height(12.dp))

    Text(
        "Private location sharing with people you care about.\nNo server. No cloud. Just you.",
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(Modifier.height(48.dp))

    FeatureRow(
        icon = Icons.Default.Lock,
        title = "End-to-end encrypted",
        subtitle = "Your location is encrypted on-device"
    )
    Spacer(Modifier.height(20.dp))
    FeatureRow(
        icon = Icons.Default.CloudOff,
        title = "No central server",
        subtitle = "Data travels peer-to-peer directly"
    )
    Spacer(Modifier.height(20.dp))
    FeatureRow(
        icon = Icons.Default.PhoneAndroid,
        title = "Stays on your phone",
        subtitle = "Your history never leaves your device"
    )

    Spacer(Modifier.height(48.dp))

    Text(
        "What should your friends and family call you?",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.fillMaxWidth()
    )

    Spacer(Modifier.height(16.dp))

    OutlinedTextField(
        value = state.displayName,
        onValueChange = vm::setDisplayName,
        label = { Text("Display name") },
        placeholder = { Text("e.g. Mom, Dad, Alice…") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
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
        Text("Get Started", style = MaterialTheme.typography.titleMedium)
    }

    Spacer(Modifier.height(24.dp))

    Text(
        "Your private key is generated locally and never leaves this device.",
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
        title = "Permissions",
        description = "LocaPeer needs location access to share your position, and camera access to scan invite codes."
    )

    Spacer(Modifier.height(48.dp))

    if (permissionsState.allPermissionsGranted) {
        LaunchedEffect(Unit) { onNext() }
    } else {
        Button(
            onClick = { permissionsState.launchMultiplePermissionRequest() },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text("Grant Permissions", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun BackgroundLocationStep(onNext: () -> Unit) {
    StepHeader(
        icon = Icons.Default.LocationOn,
        title = "Background Location",
        description = "To keep your contacts updated while the app is closed, please select 'Allow all the time' in the next screen."
    )

    Spacer(Modifier.height(48.dp))

    Button(
        onClick = onNext,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Text("Configure Location", style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun BatteryStep(onNext: () -> Unit) {
    StepHeader(
        icon = Icons.Default.BatteryChargingFull,
        title = "Battery",
        description = "Android may stop LocaPeer to save battery. To ensure reliable updates, please exclude LocaPeer from optimizations."
    )

    Spacer(Modifier.height(48.dp))

    Button(
        onClick = onNext,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Text("Disable Optimizations", style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun DoneStep(onComplete: () -> Unit) {
    StepHeader(
        icon = Icons.Default.CheckCircle,
        title = "All Set!",
        description = "Your private location network is ready."
    )

    Spacer(Modifier.height(48.dp))

    Button(
        onClick = onComplete,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Text("Start Using LocaPeer", style = MaterialTheme.typography.titleMedium)
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
