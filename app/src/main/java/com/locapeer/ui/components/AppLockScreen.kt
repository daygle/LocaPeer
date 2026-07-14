package com.locapeer.ui.components

import android.app.Activity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.locapeer.R

/**
 * Full-app overlay shown while the process is locked. Two paths to unlock:
 *
 * 1. **Auto-prompt** on first composition: if the device can satisfy the prompt
 *    (biometric enrolled OR device credentials set), BiometricPrompt is launched
 *    immediately so the user does not need to tap a button just to reach the prompt.
 * 2. **Manual retry**: a button that re-launches the prompt for users who
 *    dismissed the auto-prompt or returned from the system PIN sheet.
 *
 * NOTE: the prompt requires a [FragmentActivity] host. The LocaPeer [MainActivity]
 * extends [androidx.appcompat.app.AppCompatActivity] which is a FragmentActivity, so
 * the `LocalContext.current` cast below is safe; a future app-side switch to plain
 * ComponentActivity would also need to convert the host to FragmentActivity.
 */
@Composable
fun AppLockScreen(
    onUnlocked: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    var authAttempted by remember { mutableStateOf(false) }
    var lastError by remember { mutableStateOf<String?>(null) }
    // No enrolled biometric AND no device PIN/pattern/password means BiometricPrompt has
    // nothing to check, so the auto-prompt never fires and the unlock button would only
    // error. Detect it up front to show an actionable message instead of a silent
    // dead-end (the user removed their screen lock after enabling app-lock).
    val noCredential = activity != null && !canAuthenticate(activity)

    // Auto-attempt on first composition whenever the device *can* authenticate - the
    // visible button is only a retry affordance, since most unlocks should be one tap.
    LaunchedEffect(authAttempted) {
        if (!authAttempted && activity != null && canAuthenticate(activity)) {
            authAttempted = true
            prompt(activity, onUnlocked) { msg -> lastError = msg }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    stringResource(R.string.app_lock_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    stringResource(R.string.app_lock_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                lastError?.let { err ->
                    Text(
                        err,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(Modifier.height(8.dp))

                if (noCredential) {
                    // No screen lock on the device: the prompt can't run. Tell the user how
                    // to recover rather than leaving them on a dead button.
                    Text(
                        stringResource(R.string.app_lock_no_credential),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                } else if (activity != null) {
                    Button(
                        onClick = { prompt(activity, onUnlocked) { msg -> lastError = msg } },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Fingerprint, contentDescription = null,
                             modifier = Modifier.size(20.dp))
                        Spacer(Modifier.height(0.dp))
                        Text(
                            stringResource(R.string.app_lock_unlock),
                            modifier = Modifier.padding(start = 8.dp),
                            fontWeight = FontWeight.Medium
                        )
                    }
                } else {
                    // The host Activity is not a FragmentActivity. Without one the
                    // BiometricPrompt cannot be shown; fall back to tapping into the
                    // flagged error so the user is not stuck on a non-functional
                    // overlay.
                    Text(
                        stringResource(R.string.app_lock_activity_unsupported),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }

    // Reset the auto-prompt guard if the screen leaves composition without success
    // (e.g. Activity recreation after a system dialog), so the next composition
    // re-triggers instead of leaving the screen locked and silent. Currently unused
    // but kept (with no-op body) as the documented seam for adding an auto-retry
    // affordance later.
    DisposableEffect(Unit) {
        onDispose { }
    }
}

/** True when the device can satisfy BiometricPrompt with our chosen authenticator
 *  set; the Authenticator set should also suffice since Android 11 (API 30) onwards. */
private fun canAuthenticate(activity: Activity): Boolean {
    val manager = BiometricManager.from(activity)
    val result = manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
    return result == BiometricManager.BIOMETRIC_SUCCESS
}

/** Launch the prompt and dispatch the result to [onUnlocked] / [onError]. */
private fun prompt(
    activity: FragmentActivity,
    onUnlocked: () -> Unit,
    onError: (String) -> Unit
) {
    val executor = ContextCompat.getMainExecutor(activity)
    val callback = object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            onUnlocked()
        }
        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            // ERROR_USER_CANCELED / NEGATIVE_BUTTON fire when the user dismisses the
            // prompt; we deliberately do NOT surface those as errors since the retry
            // button on the AppLockScreen covers that case.
            if (errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                errorCode == BiometricPrompt.ERROR_CANCELED) return
            onError(errString.toString())
        }
        override fun onAuthenticationFailed() {
            // Fingerprint didn't match - leave the prompt up so the user can retry
            // without re-launching it. We don't surface this as an error either; it's
            // a normal flow during the prompt.
        }
    }
    val prompt = BiometricPrompt(activity, executor, callback)
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle(activity.getString(R.string.app_lock_prompt_title))
        .setSubtitle(activity.getString(R.string.app_lock_prompt_subtitle))
        .setAllowedAuthenticators(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )
        // No `setNegativeButtonText` because we requested DEVICE_CREDENTIAL, which
        // already provides system-supplied buttons.
        .setConfirmationRequired(false)
        .build()
    prompt.authenticate(info)
}
