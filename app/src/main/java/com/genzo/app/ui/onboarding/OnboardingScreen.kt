package com.genzo.app.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun OnboardingScreen(onContinue: () -> Unit) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("GENZO", style = MaterialTheme.typography.displaySmall)
            androidx.compose.foundation.layout.Spacer(Modifier.padding(8.dp))
            Text(
                "Your conversations belong to your devices, not our servers.\n\n" +
                    "Messages are end-to-end encrypted on this device before they ever " +
                    "leave it. The relay server that carries them can't read them.",
                style = MaterialTheme.typography.bodyMedium,
            )
            androidx.compose.foundation.layout.Spacer(Modifier.padding(16.dp))
            Button(onClick = onContinue) { Text("Get started") }
        }
    }
}
