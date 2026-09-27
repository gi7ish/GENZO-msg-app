package com.genzo.app.domain

import android.util.Base64
import com.genzo.app.crypto.DeviceIdentity
import com.genzo.app.data.LocalStore
import com.genzo.app.network.RelayApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Orchestrates crypto + network + local storage behind a small, UI-safe
 * surface. This is the module boundary from instruction #8: [DeviceIdentity]
 * (which holds the identity/session key material) is a private field here
 * and is never returned to a caller. ViewModels and Composables only ever
 * see [Conversation]/[ChatMessage]/[ConnectionStatus].
 *
 * Mirrors the orchestration in device/tests/two_device_flow.rs and the
 * device_a/device_b demo binaries from the Rust vertical slice, just
 * wrapped for a UI instead of a println-driven CLI demo.
 */
class GenzoRepository(
    private val relay: RelayApi,
    private val localStore: LocalStore,
) {
    private var device: DeviceIdentity? = null
    private val sessionLock = Mutex() // one X3DH/encrypt/decrypt call at a time per repository instance

    val myUserId: String? get() = device?.userId
    val isOnboarded: Boolean get() = device != null

    /** Onboarding step: generate identity + prekeys, register with the relay. */
    suspend fun createIdentityAndRegister(userId: String) {
        val newDevice = DeviceIdentity.create(userId)
        val registration = newDevice.generateAndRegisterPrekeys()
        relay.register(registration)
        device = newDevice
    }

    suspend fun checkConnection(): ConnectionStatus =
        if (relay.health()) ConnectionStatus.CONNECTED else ConnectionStatus.OFFLINE

    /** Fetch the peer's bundle and run X3DH. Safe to call again — a real
     * app would check whether a session already exists first; this MVP
     * always fetches a fresh bundle, matching the Rust reference's scope
     * (single one-time prekey per user, see KNOWN_LIMITATIONS). */
    suspend fun startConversationWith(peerId: String) = sessionLock.withLock {
        val d = requireDevice()
        val bundle = relay.waitForBundle(peerId)
        d.establishSessionFromBundle(bundle)
    }

    suspend fun sendMessage(peerId: String, peerDeviceId: Int, text: String): ChatMessage = sessionLock.withLock {
        val d = requireDevice()
        val (messageType, ciphertext) = d.encrypt(peerId, peerDeviceId, text.toByteArray(Charsets.UTF_8))
        val now = System.currentTimeMillis()
        relay.sendCiphertext(
            recipientId = peerId,
            senderId = d.userId,
            senderDeviceId = DeviceIdentity.DEVICE_ID,
            messageType = messageType,
            ciphertextB64 = Base64.encodeToString(ciphertext, Base64.NO_WRAP),
            sentAtUnixMs = now,
        )
        localStore.insertMessage(conversationId(d.userId, peerId), d.userId, LocalStore.Direction.OUTGOING, text, now)
        ChatMessage(senderId = d.userId, body = text, isOutgoing = true, sentAtUnixMs = now)
    }

    /** Poll the relay once, decrypt anything waiting, persist it locally,
     * and return it. Call this from a polling loop (see ChatViewModel) —
     * there is no push/websocket layer in this MVP, matching the Rust
     * relay's pull-based design. */
    suspend fun pollAndDecryptIncoming(): List<ChatMessage> = sessionLock.withLock {
        val d = requireDevice()
        val envelopes = relay.pullMessages(d.userId)
        envelopes.map { envelope ->
            val ciphertext = Base64.decode(envelope.ciphertext_b64, Base64.NO_WRAP)
            val plaintext = d.decrypt(envelope.sender_id, envelope.sender_device_id, envelope.message_type, ciphertext)
            val text = String(plaintext, Charsets.UTF_8)
            localStore.insertMessage(
                conversationId(d.userId, envelope.sender_id),
                envelope.sender_id,
                LocalStore.Direction.INCOMING,
                text,
                envelope.sent_at_unix_ms,
            )
            ChatMessage(senderId = envelope.sender_id, body = text, isOutgoing = false, sentAtUnixMs = envelope.sent_at_unix_ms)
        }
    }

    fun conversationHistory(peerId: String): List<ChatMessage> {
        val me = device?.userId ?: return emptyList()
        return localStore.messagesForConversation(conversationId(me, peerId)).map {
            ChatMessage(
                senderId = it.senderId,
                body = it.plaintextBody,
                isOutgoing = it.direction == "outgoing",
                sentAtUnixMs = it.sentAtUnixMs,
            )
        }
    }

    fun conversationList(): List<Conversation> {
        return localStore.allConversationIds().mapNotNull { convId ->
            val peer = peerIdFromConversationId(convId) ?: return@mapNotNull null
            val history = conversationHistory(peer)
            val last = history.lastOrNull() ?: return@mapNotNull null
            Conversation(peerId = peer, lastMessagePreview = last.body, lastMessageAtUnixMs = last.sentAtUnixMs)
        }
    }

    private fun requireDevice(): DeviceIdentity =
        device ?: error("No identity yet — call createIdentityAndRegister() first")

    /** Deterministic, order-independent conversation id for a pair of users,
     * mirroring the Rust reference's "conv:alice:bob" convention. */
    private fun conversationId(userA: String, userB: String): String =
        listOf(userA, userB).sorted().joinToString(":", prefix = "conv:")

    private fun peerIdFromConversationId(convId: String): String? {
        val me = device?.userId ?: return null
        val parts = convId.removePrefix("conv:").split(":")
        return parts.firstOrNull { it != me }
    }
}
