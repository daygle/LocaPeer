package com.locapeer.invite

import android.content.Intent
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.CompoundBarcodeView

private enum class InviteTab { MY_INVITE, ADD_CONTACT }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun InviteScreen(
    onNavigateBack: () -> Unit,
    inviteData: String? = null,
    inviteVm: InviteViewModel = hiltViewModel(),
    scanVm: ScanViewModel = hiltViewModel()
) {
    var selectedTab by remember { mutableStateOf(if (inviteData != null) InviteTab.ADD_CONTACT else InviteTab.MY_INVITE) }
    val context = LocalContext.current

    LaunchedEffect(inviteData) {
        if (inviteData != null) scanVm.processInviteLink(inviteData)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("QR / Invite", fontWeight = FontWeight.SemiBold) },
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
            PrimaryTabRow(selectedTabIndex = if (selectedTab == InviteTab.MY_INVITE) 0 else 1) {
                Tab(
                    selected = selectedTab == InviteTab.MY_INVITE,
                    onClick = { selectedTab = InviteTab.MY_INVITE },
                    text = { Text("My Invite") },
                    icon = { Icon(Icons.Default.QrCode2, null) }
                )
                Tab(
                    selected = selectedTab == InviteTab.ADD_CONTACT,
                    onClick = { selectedTab = InviteTab.ADD_CONTACT },
                    text = { Text("Add Contact") },
                    icon = { Icon(Icons.Default.PersonAdd, null) }
                )
            }

            AnimatedContent(
                targetState = selectedTab,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                modifier = Modifier.fillMaxSize()
            ) { tab ->
                when (tab) {
                    InviteTab.MY_INVITE -> MyInviteTab(inviteVm, context)
                    InviteTab.ADD_CONTACT -> AddContactTab(scanVm, onNavigateBack)
                }
            }
        }
    }
}

@Composable
private fun MyInviteTab(vm: InviteViewModel, context: android.content.Context) {
    val state by vm.state.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "Your QR Code",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Let someone scan this to follow your location",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }

        Card(
            modifier = Modifier.size(280.dp),
            shape = RoundedCornerShape(24.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Box(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                if (state.qrBitmap != null) {
                    Image(
                        bitmap = state.qrBitmap!!.asImageBitmap(),
                        contentDescription = "Your QR code",
                        modifier = Modifier.fillMaxSize()
                    )
                } else if (state.error) {
                    Text("Error generating QR", color = MaterialTheme.colorScheme.error)
                } else {
                    CircularProgressIndicator()
                }
            }
        }

        Button(
            onClick = {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, state.inviteLink)
                }
                context.startActivity(Intent.createChooser(intent, "Share Invite Link"))
            },
            enabled = state.inviteLink.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Icon(Icons.Default.Share, null)
            Spacer(Modifier.width(8.dp))
            Text("Share Invite Link")
        }

        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(Icons.Default.Lock, null, modifier = Modifier.size(20.dp).padding(top = 2.dp))
                Text(
                    "Your location will be encrypted specifically for the scanner's device.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun AddContactTab(vm: ScanViewModel, onDone: () -> Unit) {
    val cameraPermission = rememberPermissionState(android.Manifest.permission.CAMERA)
    val scanState by vm.scanState.collectAsState()
    var pasteText by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (!cameraPermission.status.isGranted) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier = Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.CameraAlt, null, modifier = Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Text("Camera Access Required", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Grant camera permission to scan a QR code.", textAlign = TextAlign.Center)
                Button(onClick = { cameraPermission.launchPermissionRequest() }) {
                    Text("Grant Permission")
                }
            }
        } else if (scanState.success) {
            SuccessView(scanState.addedName ?: "Contact", onDone, vm)
        } else if (scanState.pendingName != null) {
            ConfirmView(scanState.pendingName!!, vm)
        } else {
            // Scanner View
            Box(modifier = Modifier.height(300.dp).fillMaxWidth()) {
                ScannerView(vm)
                Surface(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)
                ) {
                    Text("Point camera at a LocaPeer QR", modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium)
                }
            }

            // Paste Section
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Or Paste Invite Link", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = pasteText,
                    onValueChange = { pasteText = it },
                    placeholder = { Text("locapeer://invite?data=...") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 3,
                    shape = RoundedCornerShape(16.dp)
                )
                Button(
                    onClick = { vm.processInviteLink(pasteText.trim()) },
                    enabled = pasteText.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Default.ContentPaste, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Process Link")
                }
                if (scanState.error != null) {
                    Text(scanState.error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun ScannerView(vm: ScanViewModel) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val barcodeViewRef = remember { mutableStateOf<CompoundBarcodeView?>(null) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) barcodeViewRef.value?.resume()
            else if (event == Lifecycle.Event.ON_PAUSE) barcodeViewRef.value?.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            barcodeViewRef.value?.pause()
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

@Composable
private fun SuccessView(name: String, onDone: () -> Unit, vm: ScanViewModel) {
    Column(
        modifier = Modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(Icons.Default.CheckCircle, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
        Text("Request Sent!", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("You'll be connected with $name once they accept.", textAlign = TextAlign.Center)
        Button(onClick = { vm.reset(); onDone() }, modifier = Modifier.fillMaxWidth()) {
            Text("Done")
        }
    }
}

@Composable
private fun ConfirmView(name: String, vm: ScanViewModel) {
    Column(
        modifier = Modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(Icons.Default.PersonAdd, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
        Text("Add $name?", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Add them to your contacts and share location?", textAlign = TextAlign.Center)
        Button(onClick = { vm.confirmAdd() }, modifier = Modifier.fillMaxWidth()) {
            Text("Add & Share")
        }
        TextButton(onClick = { vm.reset() }) {
            Text("Cancel")
        }
    }
}
