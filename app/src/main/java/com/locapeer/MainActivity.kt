package com.locapeer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.locapeer.beacon.HeartbeatService
import com.locapeer.crypto.KeyManager
import com.locapeer.invite.EXTRA_IS_ROLE_CHANGE
import com.locapeer.invite.EXTRA_REQUESTED_ROLE
import com.locapeer.invite.EXTRA_SENDER_NAME
import com.locapeer.invite.EXTRA_SENDER_PUBKEY
import com.locapeer.invite.EXTRA_SENDER_RELAY
import com.locapeer.messaging.MessagingViewModel
import com.locapeer.onboarding.OnboardingScreen
import com.locapeer.onboarding.OnboardingViewModel
import com.locapeer.settings.AppPreferences
import com.locapeer.supervised.SupervisionApprovalManager
import com.locapeer.ui.LocaPeerNavHost
import com.locapeer.ui.theme.LocaPeerTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Extras for intents launched from notification action buttons. Android never
 * auto-cancels a notification when an action is tapped (setAutoCancel only covers
 * body taps), so intents that open the app carry the tag/id of the notification
 * they came from and MainActivity dismisses it on arrival.
 */
const val EXTRA_CANCEL_NOTIF_TAG = "cancelNotifTag"
const val EXTRA_CANCEL_NOTIF_ID = "cancelNotifId"

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var prefs: AppPreferences
    @Inject lateinit var keyManager: KeyManager
    @Inject lateinit var approvalManager: SupervisionApprovalManager

    private val pendingNavTarget = mutableStateOf<NavTarget?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        handleIntent(intent)
        enableEdgeToEdge()
        
        setContent {
            LocaPeerTheme {
                val onboardingComplete by remember(prefs) {
                    prefs.settings.map { it.onboardingComplete }
                }.collectAsState(initial = null)
                
                val navTarget by pendingNavTarget
                val pendingApproval by approvalManager.pending.collectAsState()

                if (pendingApproval != null) {
                    val req = pendingApproval!!
                    AlertDialog(
                        onDismissRequest = { approvalManager.respond(approved = false) },
                        title = { Text("Supervision Request") },
                        text = { Text("\"${req.deviceName}\" is requesting access to their settings. Allow?") },
                        confirmButton = {
                            Button(onClick = { approvalManager.respond(approved = true) }) { Text("Approve") }
                        },
                        dismissButton = {
                            OutlinedButton(onClick = { approvalManager.respond(approved = false) }) { Text("Deny") }
                        },
                    )
                }

                Crossfade(
                    targetState = onboardingComplete,
                    animationSpec = tween(durationMillis = 400),
                    label = "root_crossfade"
                ) { state ->
                    when (state) {
                        null -> Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                        false -> OnboardingScreen(
                            vm = hiltViewModel<OnboardingViewModel>(),
                            onComplete = {}
                        )
                        true -> {
                            val messagingVm: MessagingViewModel = hiltViewModel()
                            LaunchedEffect(Unit) {
                                delay(500) // Yield to allow initial composition to finish
                                val pubHex = withContext(Dispatchers.Default) {
                                    keyManager.getPublicKeyHex()
                                } ?: return@LaunchedEffect
                                messagingVm.startListening(pubHex)
                            }
                            // Auto-start HeartbeatService on app open if enabled and permitted.
                            LaunchedEffect(Unit) {
                                delay(1000) // Stagger service start to reduce CPU spike
                                val settings = prefs.settings.first()
                                if (!settings.heartbeatEnabled) return@LaunchedEffect
                                val hasLocation = (ContextCompat.checkSelfPermission(
                                    this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION
                                ) == PackageManager.PERMISSION_GRANTED) ||
                                (ContextCompat.checkSelfPermission(
                                    this@MainActivity, Manifest.permission.ACCESS_COARSE_LOCATION
                                ) == PackageManager.PERMISSION_GRANTED)
                                if (!hasLocation) return@LaunchedEffect
                                val intent = Intent(this@MainActivity, HeartbeatService::class.java)
                                startForegroundService(intent)
                            }
                            LocaPeerNavHost(
                                initialNavTarget = navTarget,
                                onNavTargetConsumed = { pendingNavTarget.value = null },
                                prefs = prefs
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent?.getStringExtra(EXTRA_CANCEL_NOTIF_TAG)?.let { tag ->
            val id = intent.getIntExtra(EXTRA_CANCEL_NOTIF_ID, -1)
            if (id >= 0) {
                val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
                nm.cancel(tag, id)
            }
            intent.removeExtra(EXTRA_CANCEL_NOTIF_TAG)
        }
        val action = intent?.action
        val data = intent?.data
        if (action == Intent.ACTION_VIEW && data != null && data.scheme == "locapeer") {
            val inviteData = data.getQueryParameter("data")
            if (inviteData != null) {
                pendingNavTarget.value = NavTarget("scan", inviteData, "")
            }
            // Clear the deep-link so it isn't replayed on process-death recreation
            setIntent(Intent(intent).apply { this.data = null; this.action = null })
            return
        }

        val navigateTo = intent?.getStringExtra("navigateTo") ?: return
        if (navigateTo == "share-request") {
            val pubkey = intent.getStringExtra(EXTRA_SENDER_PUBKEY) ?: return
            val name = intent.getStringExtra(EXTRA_SENDER_NAME) ?: ""
            val relay = intent.getStringExtra(EXTRA_SENDER_RELAY) ?: ""
            val isRoleChange = intent.getBooleanExtra(EXTRA_IS_ROLE_CHANGE, false)
            val requestedRole = intent.getStringExtra(EXTRA_REQUESTED_ROLE)
            pendingNavTarget.value = NavTarget("share-request", pubkey, name, relay, isRoleChange, requestedRole)
        } else {
            val peerId = intent.getStringExtra("openChat") ?: intent.getStringExtra("highlightPeer")
            val peerName = intent.getStringExtra("peerName") ?: ""
            pendingNavTarget.value = NavTarget(navigateTo, peerId, peerName)
        }
        // Strip consumed extras so process-death recreation doesn't replay the notification intent
        setIntent(Intent(intent).apply { removeExtra("navigateTo") })
    }
}

data class NavTarget(val route: String, val peerId: String?, val peerName: String, val extra: String? = null, val isRoleChange: Boolean = false, val requestedRole: String? = null)
