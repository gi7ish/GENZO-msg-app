package com.genzo.app.ui.conversations

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.genzo.app.domain.ConnectionStatus
import com.genzo.app.domain.Conversation
import com.genzo.app.domain.GenzoRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ConversationListViewModel(private val repository: GenzoRepository) : ViewModel() {

    var connectionStatus by mutableStateOf(ConnectionStatus.CONNECTING)
        private set

    var conversations by mutableStateOf<List<Conversation>>(emptyList())
        private set

    var startingConversationError by mutableStateOf<String?>(null)
        private set

    val myUserId: String? get() = repository.myUserId

    init {
        viewModelScope.launch {
            while (true) {
                connectionStatus = repository.checkConnection()
                conversations = repository.conversationList()
                delay(3000)
            }
        }
    }

    /** New-conversation flow: fetch the peer's bundle, run X3DH, then hand
     * back control (the caller navigates to ChatScreen, which sends the
     * first message). */
    fun startConversation(peerId: String, onStarted: (String) -> Unit) {
        val trimmed = peerId.trim()
        if (trimmed.isEmpty() || trimmed == myUserId) {
            startingConversationError = "Enter a different user's username."
            return
        }
        startingConversationError = null
        viewModelScope.launch {
            try {
                repository.startConversationWith(trimmed)
                onStarted(trimmed)
            } catch (e: Exception) {
                startingConversationError = e.message ?: "Could not start a conversation with $trimmed."
            }
        }
    }
}
