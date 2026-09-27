package com.genzo.app.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Thrown for both transport failures (status = 0) and non-200 relay responses. */
class RelayClientException(val status: Int, message: String) : Exception(message)

private val JSON = Json { ignoreUnknownKeys = true }
private val JSON_MEDIA_TYPE = "application/json".toMediaType()

/**
 * Talks to the same relay used by the Rust vertical slice
 * (vertical-slice/relay). Same four routes, same JSON shapes — see
 * network/WireModels.kt and device/src/relay_client.rs for the Rust side of
 * this contract.
 *
 * `baseUrl` example: "http://10.0.2.2:8765" when the relay runs on your dev
 * machine and this app runs in the Android emulator (10.0.2.2 is the
 * emulator's alias for the host's localhost) — see
 * res/xml/network_security_config.xml for why that host specifically is
 * allowed over plain HTTP.
 */
class RelayApi(private val baseUrl: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun register(req: RegisterRequest) = withContext(Dispatchers.IO) {
        val body = JSON.encodeToString(req).toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder().url("$baseUrl/v1/register").post(body).build()
        executeExpectingAck(request)
    }

    suspend fun fetchBundle(userId: String): PreKeyBundleWire = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$baseUrl/v1/prekey_bundle/$userId").get().build()
        execute(request) { JSON.decodeFromString(PreKeyBundleWire.serializer(), it) }
    }

    suspend fun sendCiphertext(
        recipientId: String,
        senderId: String,
        senderDeviceId: Int,
        messageType: Int,
        ciphertextB64: String,
        sentAtUnixMs: Long,
    ) = withContext(Dispatchers.IO) {
        val req = SendEnvelopeRequest(senderId, senderDeviceId, messageType, ciphertextB64, sentAtUnixMs)
        val body = JSON.encodeToString(req).toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder().url("$baseUrl/v1/messages/$recipientId").post(body).build()
        executeExpectingAck(request)
    }

    suspend fun pullMessages(userId: String): List<EnvelopeOut> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$baseUrl/v1/messages/$userId").get().build()
        execute(request) { JSON.decodeFromString(PullResponse.serializer(), it).envelopes }
    }

    suspend fun health(): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("$baseUrl/v1/health").get().build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (_: IOException) {
            false
        }
    }

    /** Poll [fetchBundle] until the peer has registered, or give up. Mirrors
     * `relay_client::wait_for_bundle` — needed because two independent
     * Android devices/emulators won't register at exactly the same instant. */
    suspend fun waitForBundle(userId: String, maxAttempts: Int = 100, delayMs: Long = 300): PreKeyBundleWire {
        var lastError: Exception? = null
        repeat(maxAttempts) {
            try {
                return fetchBundle(userId)
            } catch (e: Exception) {
                lastError = e
                delay(delayMs)
            }
        }
        throw lastError ?: RelayClientException(0, "timed out waiting for $userId to register")
    }

    /** Poll [pullMessages] until at least one envelope arrives, or give up. */
    suspend fun waitForMessage(userId: String, maxAttempts: Int = 200, delayMs: Long = 300): EnvelopeOut {
        repeat(maxAttempts) {
            val msgs = pullMessages(userId)
            if (msgs.isNotEmpty()) return msgs.first()
            delay(delayMs)
        }
        throw RelayClientException(0, "timed out waiting for a message for $userId")
    }

    private fun executeExpectingAck(request: Request) {
        execute(request) { JSON.decodeFromString(Ack.serializer(), it) }
    }

    private fun <T> execute(request: Request, parse: (String) -> T): T {
        try {
            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val message = try {
                        JSON.decodeFromString(ErrorResponse.serializer(), bodyStr).error
                    } catch (_: Exception) {
                        bodyStr.ifBlank { "HTTP ${response.code}" }
                    }
                    throw RelayClientException(response.code, message)
                }
                return parse(bodyStr)
            }
        } catch (e: IOException) {
            throw RelayClientException(0, e.message ?: "network error")
        }
    }
}
