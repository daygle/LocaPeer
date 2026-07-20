package com.locapeer.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import com.locapeer.ui.theme.locaPeerTopAppBarColors
import com.locapeer.ui.components.CardDivider
import com.locapeer.ui.components.SettingsCard
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionState
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale
import com.locapeer.R
import com.locapeer.onboarding.PermissionManager

/**
 * Dedicated permission center. Consolidates every OS-level permission and system access the app
 * relies on into one place so the user can review each one's status and fix the ones that are not
 * granted, rather than hunting for them scattered across the Settings sections.
 *
 * Runtime permissions use Accompanist state (which re-reads the grant on resume). Background
 * location and battery optimization are settings-only grants on modern Android, so those rows just
 * report status and route to the relevant system screen.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun PermissionsScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current

    val locationPermission = rememberPermissionState(Manifest.permission.ACCESS_FINE_LOCATION)
    val cameraPermission = rememberPermissionState(Manifest.permission.CAMERA)
    val notificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        rememberPermissionState(Manifest.permission.POST_NOTIFICATIONS) else null
    val motionPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        rememberPermissionState(Manifest.permission.ACTIVITY_RECOGNITION) else null

    // Background location and battery optimization can change while the app is backgrounded (the
    // user toggles them in system settings), so re-read them whenever we return to the foreground.
    var backgroundLocationGranted by remember { mutableStateOf(PermissionManager.hasBackgroundLocation(context)) }
    var batteryOptimizationIgnored by remember { mutableStateOf(PermissionManager.isIgnoringBatteryOptimizations(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                backgroundLocationGranted = PermissionManager.hasBackgroundLocation(context)
                batteryOptimizationIgnored = PermissionManager.isIgnoringBatteryOptimizations(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = locaPeerTopAppBarColors(),
                title = { Text(stringResource(R.string.settings_permissions)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                stringResource(R.string.permissions_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp)
            )

            SettingsCard(
                headerIcon = Icons.Default.Security,
                headerTitle = stringResource(R.string.settings_permissions)
            ) {
                RuntimePermissionRow(
                    title = stringResource(R.string.perm_location_title),
                    grantedText = stringResource(R.string.perm_location_granted),
                    deniedText = stringResource(R.string.perm_location_denied),
                    permission = locationPermission
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    CardDivider()
                    SystemAccessRow(
                        title = stringResource(R.string.perm_background_location_title),
                        granted = backgroundLocationGranted,
                        grantedText = stringResource(R.string.perm_background_location_granted),
                        deniedText = stringResource(R.string.perm_background_location_denied),
                        onClick = { openAppDetailsSettings(context) }
                    )
                }
                CardDivider()
                RuntimePermissionRow(
                    title = stringResource(R.string.perm_camera_title),
                    grantedText = stringResource(R.string.perm_camera_granted),
                    deniedText = stringResource(R.string.perm_camera_denied),
                    permission = cameraPermission
                )
                if (notificationPermission != null) {
                    CardDivider()
                    RuntimePermissionRow(
                        title = stringResource(R.string.perm_notifications_title),
                        grantedText = stringResource(R.string.perm_notifications_granted),
                        deniedText = stringResource(R.string.perm_notifications_denied),
                        permission = notificationPermission
                    )
                }
                if (motionPermission != null) {
                    CardDivider()
                    RuntimePermissionRow(
                        title = stringResource(R.string.settings_motion_detection),
                        grantedText = stringResource(R.string.settings_motion_detection_on),
                        deniedText = stringResource(R.string.settings_motion_detection_off),
                        permission = motionPermission
                    )
                }
                CardDivider()
                SystemAccessRow(
                    title = stringResource(R.string.settings_battery_optimization),
                    granted = batteryOptimizationIgnored,
                    grantedText = stringResource(R.string.settings_battery_optimization_unrestricted),
                    deniedText = stringResource(R.string.settings_battery_optimization_restricted),
                    onClick = { PermissionManager.requestBatteryOptimizationExemption(context) }
                )
            }
        }
    }
}

/**
 * A row for a standard runtime permission. When granted it shows a checkmark and is inert;
 * otherwise tapping requests the permission, or - once it has been permanently denied (asked
 * before and the system will no longer prompt) - routes to the app's system settings so the user
 * can grant it there.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun RuntimePermissionRow(
    title: String,
    grantedText: String,
    deniedText: String,
    permission: PermissionState
) {
    val context = LocalContext.current
    val granted = permission.status.isGranted
    var asked by remember { mutableStateOf(false) }
    PermissionStatusRow(
        title = title,
        granted = granted,
        statusText = if (granted) grantedText else deniedText,
        onClick = {
            when {
                granted -> openAppDetailsSettings(context)
                asked && !permission.status.shouldShowRationale -> openAppDetailsSettings(context)
                else -> {
                    asked = true
                    permission.launchPermissionRequest()
                }
            }
        }
    )
}

/**
 * A row for a system access that is not a standard runtime permission (background location on
 * modern Android, battery optimization exemption): status is read from the system and tapping
 * routes to the relevant system screen.
 */
@Composable
private fun SystemAccessRow(
    title: String,
    granted: Boolean,
    grantedText: String,
    deniedText: String,
    onClick: () -> Unit
) {
    PermissionStatusRow(
        title = title,
        granted = granted,
        statusText = if (granted) grantedText else deniedText,
        onClick = onClick
    )
}

@Composable
private fun PermissionStatusRow(
    title: String,
    granted: Boolean,
    statusText: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = if (granted) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        if (granted) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        } else {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

private fun openAppDetailsSettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null)
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}
