package com.locapeer.invite

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.CompoundBarcodeView

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun InviteScreen(
    onNavigateBack: () -> Unit,
    inviteData: String? = null,
    inviteVm: InviteViewModel = hiltViewModel(),
    scanVm: ScanViewModel = hiltViewModel()
) {
    var selectedTab by remember { mutableIntStateOf(if (inviteData != null) 1 else 0) }
    val context = LocalContext.current

    // Pre-process a deep-linked invite on the scan tab
    LaunchedEffect(inviteData) {
        if (inviteData != null) scanVm.processInviteLink(inviteData)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("QR") },
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
            PrimaryTabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("My QR Code") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.QrCodeScanner, contentDescription = null) },
                    text = { Text("Scan QR") }
                )
            }

            when (selectedTab) {
                0 -> MyQrTab(inviteVm, context)
                1 -> ScanTab(scanVm, onNavigateBack)
            }
        }
    }
}

@Composable
private fun MyQrTab(vm: InviteViewModel, context: android.content.Context) {
    val state by vm.state.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            "Show this QR code to someone who wants to track your location.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        when {
            state.error -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.ErrorOutline,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Text(
                        "Could not generate QR code.\nCheck your settings and try again.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            state.qrBitmap != null -> {
                val bitmap = state.qrBitmap!!
                Card(
                    modifier = Modifier.size(280.dp),
                    elevation = CardDefaults.cardElevation(4.dp)
                ) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Invite QR code",
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp)
                    )
                }
            }
            else -> CircularProgressIndicator()
        }

        Text(
            "Your ID: ${state.publicKeyHex.take(16)}…",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline
        )

        Text(
            "The scanner will automatically be added as a subscriber. Your location will be encrypted specifically for their device.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.weight(1f))

        if (state.inviteLink.isNotEmpty()) {
            Button(
                onClick = {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, "Connect with me on LocaPeer: ${state.inviteLink}")
                    }
                    context.startActivity(Intent.createChooser(intent, "Share Invite"))
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Default.Share, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Share Invite Link")
            }
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun ScanTab(vm: ScanViewModel, onDone: () -> Unit) {
    val cameraPermission = rememberPermissionState(android.Manifest.permission.CAMERA)
    val scanState by vm.scanState.collectAsState()
    var showPasteDialog by remember { mutableStateOf(false) }

    if (showPasteDialog) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showPasteDialog = false },
            title = { Text("Paste Invite Link") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("locapeer://invite?data=...") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 4
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showPasteDialog = false
                        vm.processInviteLink(text.trim())
                    },
                    enabled = text.isNotBlank()
                ) {
                    Text("Add")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPasteDialog = false }) { Text("Cancel") }
            }
        )
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        when {
            // ... (rest of the when block)
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
                    Button(onClick = { vm.reset(); onDone() }) { Text("Done") }
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

                // Overlay the "Paste Link" button
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 32.dp),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    ElevatedButton(
                        onClick = { showPasteDialog = true },
                        colors = ButtonDefaults.elevatedButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f)
                        )
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Paste Invite Link")
                    }
                }
            }
        }
    }
}
