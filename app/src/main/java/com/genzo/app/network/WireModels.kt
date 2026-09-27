package com.genzo.app.network

import kotlinx.serialization.Serializable

/**
 * Mirrors `common::RegisterRequest` (vertical-slice/common/src/lib.rs)
 * field-for-field. Only public material + one one-time prekey, matching the
 * same narrow MVP scope as the Rust reference (see its doc comment for why).
 */
@Serializable
data class RegisterRequest(
    val user_id: String,
    val device_id: Int,
    val registration_id: Int,
    val identity_key_b64: String,
    val signed_prekey_id: Int,
    val signed_prekey_public_b64: String,
    val signed_prekey_signature_b64: String,
    val one_time_prekey_id: Int,
    val one_time_prekey_public_b64: String,
    val kyber_prekey_id: Int,
    val kyber_prekey_public_b64: String,
    val kyber_prekey_signature_b64: String,
)

/** Mirrors `common::PreKeyBundleWire`. */
@Serializable
data class PreKeyBundleWire(
    val user_id: String,
    val device_id: Int,
    val registration_id: Int,
    val identity_key_b64: String,
    val signed_prekey_id: Int,
    val signed_prekey_public_b64: String,
    val signed_prekey_signature_b64: String,
    val one_time_prekey_id: Int? = null,
    val one_time_prekey_public_b64: String? = null,
    val kyber_prekey_id: Int,
    val kyber_prekey_public_b64: String,
    val kyber_prekey_signature_b64: String,
)

/** Mirrors `common::SendEnvelopeRequest`. */
@Serializable
data class SendEnvelopeRequest(
    val sender_id: String,
    val sender_device_id: Int,
    val message_type: Int,
    val ciphertext_b64: String,
    val sent_at_unix_ms: Long,
)

/** Mirrors `common::EnvelopeOut`. */
@Serializable
data class EnvelopeOut(
    val sender_id: String,
    val sender_device_id: Int,
    val message_type: Int,
    val ciphertext_b64: String,
    val sent_at_unix_ms: Long,
)

/** Mirrors `common::PullResponse`. */
@Serializable
data class PullResponse(
    val envelopes: List<EnvelopeOut> = emptyList(),
)

/** Mirrors `common::Ack`. */
@Serializable
data class Ack(val ok: Boolean)

/** Mirrors `common::ErrorResponse`. */
@Serializable
data class ErrorResponse(val error: String)
