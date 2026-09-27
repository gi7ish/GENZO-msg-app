package com.genzo.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.genzo.app.domain.ConnectionStatus

@Composable
fun ConnectionStatusBar(status: ConnectionStatus) {
    val (label, color) = when (status) {
        ConnectionStatus.CONNECTED -> "Connected to relay" to MaterialTheme.colorScheme.primaryContainer
        ConnectionStatus.CONNECTING -> "Connecting…" to MaterialTheme.colorScheme.surfaceVariant
        ConnectionStatus.OFFLINE -> "Relay unreachable" to MaterialTheme.colorScheme.errorContainer
    }
    Text(
        text = label,
        modifier = Modifier
            .fillMaxWidth()
            .background(color)
            .padding(6.dp),
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.labelSmall,
    )
}
