package com.locapeer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import com.locapeer.crypto.KeyManager
import com.locapeer.messaging.MessagingViewModel
import com.locapeer.onboarding.OnboardingScreen
import com.locapeer.onboarding.OnboardingViewModel
import com.locapeer.settings.AppPreferences
import com.locapeer.ui.LocaPeerNavHost
import com.locapeer.ui.theme.LocaPeerTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.map
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var prefs: AppPreferences
    @Inject lateinit var keyManager: KeyManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LocaPeerTheme {
                val onboardingComplete by prefs.settings
                    .map { it.onboardingComplete }
                    .collectAsState(initial = null)

                when (onboardingComplete) {
                    null -> Box(modifier = Modifier.fillMaxSize())
                    false -> {
                        OnboardingScreen(
                            vm = hiltViewModel<OnboardingViewModel>(),
                            onComplete = { /* recomposition will flip to true */ }
                        )
                    }
                    true -> {
                        val messagingVm: MessagingViewModel = hiltViewModel()
                        LaunchedEffect(Unit) {
                            val pubHex = keyManager.getPublicKeyHexBlocking() ?: return@LaunchedEffect
                            messagingVm.startListening(pubHex)
                        }
                        LocaPeerNavHost()
                    }
                }
            }
        }
    }
}
