package com.locapeer.invite

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.CompoundBarcodeView
import com.locapeer.R

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun ScanScreen(
    inviteData: String? = null,
    onNavigateBack: () -> Unit,
    vm: ScanViewModel = hiltViewModel()
) {
    val cameraPermission = rememberPermissionState(android.Manifest.permission.CAMERA)
    val scanState by vm.scanState.collectAsState()

    LaunchedEffect(inviteData) {
        if (inviteData != null) {
            vm.processInviteLink(inviteData)
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.scan_title)) }) }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center
        ) {
            when {
                !cameraPermission.status.isGranted -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Text(stringResource(R.string.scan_camera_needed))
                        Button(onClick = { cameraPermission.launchPermissionRequest() }) {
                            Text(stringResource(R.string.scan_grant_permission))
                        }
                    }
                }
                scanState.pendingName != null -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Text(
                            stringResource(R.string.scan_add_contact_title),
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            stringResource(R.string.scan_add_contact_message, scanState.pendingName ?: ""),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(onClick = { vm.reset() }) { Text(stringResource(R.string.common_cancel)) }
                            Button(onClick = { vm.confirmAdd() }) { Text(stringResource(R.string.scan_add_share)) }
                        }
                    }
                }
                scanState.success -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Text(
                            stringResource(R.string.scan_request_sent_title),
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            stringResource(R.string.scan_request_sent_message, scanState.addedName ?: ""),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Button(onClick = onNavigateBack) { Text(stringResource(R.string.common_done)) }
                    }
                }
                scanState.error != null -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Text(stringResource(R.string.scan_failed, scanState.error ?: ""))
                        Button(onClick = { vm.reset() }) { Text(stringResource(R.string.common_try_again)) }
                    }
                }
                else -> {
                    val lifecycleOwner = LocalLifecycleOwner.current
                    val barcodeViewRef = remember { mutableStateOf<CompoundBarcodeView?>(null) }

                    DisposableEffect(lifecycleOwner) {
                        val observer = LifecycleEventObserver { _, event ->
                            when (event) {
                                Lifecycle.Event.ON_RESUME -> barcodeViewRef.value?.resume()
                                Lifecycle.Event.ON_PAUSE -> barcodeViewRef.value?.pause()
                                else -> Unit
                            }
                        }
                        lifecycleOwner.lifecycle.addObserver(observer)
                        onDispose {
                            lifecycleOwner.lifecycle.removeObserver(observer)
                            barcodeViewRef.value?.pause()
                            barcodeViewRef.value = null
                        }
                    }

                    AndroidView(
                        factory = { ctx ->
                            CompoundBarcodeView(ctx).apply {
                                decodeContinuous(object : BarcodeCallback {
                                    override fun barcodeResult(result: BarcodeResult) {
                                        vm.processQrCode(result.text)
                                    }
                                })
                                barcodeViewRef.value = this
                                resume()
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}
