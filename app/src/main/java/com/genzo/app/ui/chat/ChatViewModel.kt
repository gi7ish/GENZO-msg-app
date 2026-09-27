package com.genzo.app.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.genzo.app.crypto.DeviceIdentity
import com.genzo.app.domain.ChatMessage
import com.genzo.app.domain.GenzoRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ChatViewModel(private val repository: GenzoRepository, private val peerId: String) : ViewModel() {

    var messages by mutableStateOf<List<ChatMessage>>(emptyList())
        private set

    var draft by mutableStateOf("")

    var sendError by mutableStateOf<String?>(null)
        private set

    init {
        messages = repository.conversationHistory(peerId)
        // Polling loop, not push — mirrors the Rust relay's pull-based
        // design (relay_client::wait_for_message / pull_messages). A real
        // deployment would want a push channel (long-poll or websocket) so
        // this doesn't need a fixed interval; out of scope for this MVP.
        viewModelScope.launch {
            while (true) {
                try {
                    val incoming = repository.pollAndDecryptIncoming()
                    if (incoming.any { it.senderId == peerId }) {
                        messages = repository.conversationHistory(peerId)
                    }
                } catch (_: Exception) {
                    // transient network hiccup — next poll will retry. A
                    // visible retry/backoff indicator is a reasonable next
                    // increment, not implemented in this MVP.
                }
                delay(2000)
            }
        }
    }

    fun send() {
        val text = draft.trim()
        if (text.isEmpty()) return
        draft = ""
        sendError = null
        viewModelScope.launch {
            try {
                repository.sendMessage(peerId, DeviceIdentity.DEVICE_ID, text)
                messages = repository.conversationHistory(peerId)
            } catch (e: Exception) {
                sendError = e.message ?: "Failed to send."
            }
        }
    }
}
