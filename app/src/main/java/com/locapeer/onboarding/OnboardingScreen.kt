package com.locapeer.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.locapeer.invite.QrCodeGenerator

@Composable
fun OnboardingScreen(
    vm: OnboardingViewModel,
    onComplete: () -> Unit
) {
    val state by vm.state.collectAsState()
    val qrGenerator = remember { QrCodeGenerator() }
    val qrBitmap = remember(state.publicKeyHex) {
        if (state.publicKeyHex.isNotEmpty()) qrGenerator.generate(state.publicKeyHex, 256)
        else null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically)
    ) {
        Text(
            "Welcome to LocaPeer",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center
        )
        Text(
            "Private, serverless family location sharing.\nNo central server ever sees your data.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        qrBitmap?.let { bmp ->
            Card(elevation = CardDefaults.cardElevation(4.dp)) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "Your public key QR",
                    modifier = Modifier
                        .size(200.dp)
                        .padding(8.dp)
                )
            }
            Text(
                "This is your unique identity.\nShare it with family so they can add you.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        OutlinedTextField(
            value = state.displayName,
            onValueChange = vm::setDisplayName,
            label = { Text("Your display name") },
            placeholder = { Text("e.g. Mom, Dad, Alice…") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            onClick = { vm.complete(onComplete) },
            enabled = state.displayName.isNotBlank() && !state.isLoading,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Get Started")
        }
    }
}
