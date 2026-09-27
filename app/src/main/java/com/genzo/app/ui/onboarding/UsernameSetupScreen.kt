package com.genzo.app.ui.onboarding

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun UsernameSetupScreen(viewModel: OnboardingViewModel, onDone: () -> Unit) {
    var username by remember { mutableStateOf("") }
    val state = viewModel.state

    LaunchedEffect(state) {
        if (state is OnboardingState.Done) onDone()
    }

    Scaffold { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp)) {
            Text("Choose a username", style = MaterialTheme.typography.headlineSmall)
            Text(
                "No phone number, no email required. This is registered with the " +
                    "relay along with your public identity key and prekeys — never " +
                    "your private key.",
                style = MaterialTheme.typography.bodySmall,
            )
            androidx.compose.foundation.layout.Spacer(Modifier.padding(8.dp))
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("Username") },
                enabled = state !is OnboardingState.Registering,
                modifier = Modifier.fillMaxWidth(),
            )
            androidx.compose.foundation.layout.Spacer(Modifier.padding(8.dp))
            when (state) {
                is OnboardingState.Registering -> CircularProgressIndicator()
                is OnboardingState.Error -> Text(
                    state.message,
                    color = MaterialTheme.colorScheme.error,
                )
                else -> {}
            }
            Button(
                onClick = { viewModel.createIdentity(username) },
                enabled = state !is OnboardingState.Registering,
            ) {
                Text("Create identity")
            }
        }
    }
}
