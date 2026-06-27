package com.locapeer.invite

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
        topBar = { TopAppBar(title = { Text("Scan Invite") }) }
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
                        Text("Camera permission is needed to scan QR codes.")
                        Button(onClick = { cameraPermission.launchPermissionRequest() }) {
                            Text("Grant Permission")
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
                            "Added ${scanState.addedName}!",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            "You will now receive location updates from them.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Button(onClick = onNavigateBack) { Text("Done") }
                    }
                }
                scanState.error != null -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Text("Scan failed: ${scanState.error}")
                        Button(onClick = { vm.reset() }) { Text("Try Again") }
                    }
                }
                else -> {
                    val lifecycleOwner = LocalLifecycleOwner.current
                    var barcodeViewRef: CompoundBarcodeView? = null

                    DisposableEffect(lifecycleOwner) {
                        val observer = LifecycleEventObserver { _, event ->
                            when (event) {
                                Lifecycle.Event.ON_RESUME -> barcodeViewRef?.resume()
                                Lifecycle.Event.ON_PAUSE -> barcodeViewRef?.pause()
                                else -> Unit
                            }
                        }
                        lifecycleOwner.lifecycle.addObserver(observer)
                        onDispose {
                            lifecycleOwner.lifecycle.removeObserver(observer)
                            barcodeViewRef?.pause()
                            barcodeViewRef = null
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
                                barcodeViewRef = this
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
