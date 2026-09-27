package com.genzo.app.domain

enum class ConnectionStatus { CONNECTED, CONNECTING, OFFLINE }

data class Conversation(
    val peerId: String,
    val lastMessagePreview: String,
    val lastMessageAtUnixMs: Long,
)

data class ChatMessage(
    val senderId: String,
    val body: String,
    val isOutgoing: Boolean,
    val sentAtUnixMs: Long,
)
