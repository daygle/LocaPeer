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
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
                .padding(horizontal = 32.dp)
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
    // Brand hero
    Box(
        modifier = Modifier
            .size(96.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Default.MyLocation,
            contentDescription = null,
            modifier = Modifier.size(52.dp),
            tint = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }

    Spacer(Modifier.height(24.dp))

    Text(
        "LocaPeer",
        style = MaterialTheme.typography.displaySmall,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center
    )

    Spacer(Modifier.height(8.dp))

    Text(
        "Private family location sharing.\nNo server. No cloud. Just you.",
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(Modifier.height(40.dp))

    // Feature highlights
    FeatureRow(
        icon = Icons.Default.Lock,
        title = "End-to-end encrypted",
        subtitle = "Your location is encrypted on-device, just for your family"
    )
    Spacer(Modifier.height(16.dp))
    FeatureRow(
        icon = Icons.Default.CloudOff,
        title = "No central server",
        subtitle = "Data travels peer-to-peer — no company ever sees it"
    )
    Spacer(Modifier.height(16.dp))
    FeatureRow(
        icon = Icons.Default.PhoneAndroid,
        title = "Stays on your phone",
        subtitle = "Location history and messages never leave your device"
    )

    Spacer(Modifier.height(48.dp))

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

    Spacer(Modifier.height(32.dp))

    Text(
        "What should your family call you?",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.fillMaxWidth()
    )

    Spacer(Modifier.height(12.dp))

    OutlinedTextField(
        value = state.displayName,
        onValueChange = vm::setDisplayName,
        label = { Text("Display name") },
        placeholder = { Text("e.g. Mom, Dad, Alice…") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    )

    Spacer(Modifier.height(24.dp))

    Button(
        onClick = { vm.nextStep() },
        enabled = state.displayName.isNotBlank() && !state.isLoading,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Text("Get Started", style = MaterialTheme.typography.titleMedium)
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun PermissionsStep(
    permissionsState: com.google.accompanist.permissions.MultiplePermissionsState,
    onNext: () -> Unit
) {
    Text("Permissions", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(16.dp))
    Text(
        "LocaPeer needs location access to share your position, and camera access to scan invite codes.",
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(32.dp))

    if (permissionsState.allPermissionsGranted) {
        onNext()
    } else {
        Button(onClick = { permissionsState.launchMultiplePermissionRequest() }) {
            Text("Grant Permissions")
        }
    }
}

@Composable
private fun BackgroundLocationStep(onNext: () -> Unit) {
    Text("Background Location", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(16.dp))
    Text(
        "To keep your family updated while the app is closed, please select 'Allow all the time' in the next screen.",
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(32.dp))
    Button(onClick = onNext) {
        Text("Configure Location")
    }
}

@Composable
private fun BatteryStep(onNext: () -> Unit) {
    Text("Battery Optimization", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(16.dp))
    Text(
        "Android may stop LocaPeer to save battery. To ensure reliable updates, please exclude LocaPeer from battery optimizations.",
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(32.dp))
    Button(onClick = onNext) {
        Text("Disable Optimizations")
    }
}

@Composable
private fun DoneStep(onComplete: () -> Unit) {
    Text("All Set!", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(16.dp))
    Text(
        "Your private location network is ready.",
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(32.dp))
    Button(onClick = onComplete) {
        Text("Start Using LocaPeer")
    }
}

@Composable
private fun FeatureRow(icon: ImageVector, title: String, subtitle: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
        Column(modifier = Modifier.padding(top = 2.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
