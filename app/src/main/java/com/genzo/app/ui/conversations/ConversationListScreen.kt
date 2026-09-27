package com.genzo.app.ui.conversations

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.genzo.app.domain.Conversation
import com.genzo.app.ui.common.ConnectionStatusBar

@Composable
fun ConversationListScreen(viewModel: ConversationListViewModel, onOpenConversation: (String) -> Unit) {
    var showNewConversationDialog by remember { mutableStateOf(false) }
    var newPeerId by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(title = { Text("GENZO — ${viewModel.myUserId.orEmpty()}") })
                ConnectionStatusBar(viewModel.connectionStatus)
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showNewConversationDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = "New conversation")
            }
        },
    ) { padding ->
        if (viewModel.conversations.isEmpty()) {
            Column(modifier = Modifier.fillMaxWidth().padding(padding).padding(24.dp)) {
                Text("No conversations yet. Tap + to message someone by username.")
            }
        } else {
            LazyColumn(modifier = Modifier.padding(padding)) {
                items(viewModel.conversations) { conversation: Conversation ->
                    ConversationRow(conversation, onClick = { onOpenConversation(conversation.peerId) })
                }
            }
        }
    }

    if (showNewConversationDialog) {
        AlertDialog(
            onDismissRequest = { showNewConversationDialog = false },
            title = { Text("Message someone") },
            text = {
                Column {
                    OutlinedTextField(
                        value = newPeerId,
                        onValueChange = { newPeerId = it },
                        label = { Text("Their username") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    viewModel.startingConversationError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.startConversation(newPeerId) { peerId ->
                        showNewConversationDialog = false
                        newPeerId = ""
                        onOpenConversation(peerId)
                    }
                }) { Text("Start") }
            },
            dismissButton = {
                TextButton(onClick = { showNewConversationDialog = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ConversationRow(conversation: Conversation, onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp)) {
        Column {
            Text(conversation.peerId, style = MaterialTheme.typography.titleMedium)
            Text(conversation.lastMessagePreview, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
        }
    }
}
