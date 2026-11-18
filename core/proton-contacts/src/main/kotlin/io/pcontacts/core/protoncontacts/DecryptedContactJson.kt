// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.protoncontacts

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Byte form of a [DecryptedContact] for the persisted merge base
 * (ADR-0017 §3). A versioned envelope so a future field change can
 * refuse — rather than misread — an older blob: any decode problem,
 * including a version mismatch, yields null, which the sync engine
 * treats as "no merge base" (a user-visible conflict, never a merge
 * against an empty contact).
 *
 * The bytes are plaintext contact content; the caller seals them
 * under the Keystore KEK before they touch the database (ADR-0018).
 */
object DecryptedContactJson {

    /**
     * 3 since the phone's photo digest tells "no photo" (null) from "not known"
     * ([PhotoHash.UNKNOWN]); 2 since the photo digests joined the envelope (ADR-0018). A v1 blob
     * reads as "no base".
     */
    const val VERSION = 3

    /**
     * Still read. In a v2 blob a missing phone digest meant "no photo" and, in bases 2.6.0 saved
     * for a row edited during a pull, "not known"; nothing tells the two apart, so it is read as
     * not known: a photo removed on the phone is then asked about, never put back unasked
     * (review 2026-11-18).
     */
    private const val LEGACY_VERSION = 2

    @Serializable
    private data class Envelope(val v: Int, val contact: DecryptedContact)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(contact: DecryptedContact): ByteArray =
        json.encodeToString(Envelope.serializer(), Envelope(VERSION, contact)).encodeToByteArray()

    fun decode(bytes: ByteArray): DecryptedContact? {
        val envelope = try {
            json.decodeFromString(Envelope.serializer(), bytes.decodeToString())
        } catch (_: SerializationException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
        return when (envelope.v) {
            VERSION -> envelope.contact
            LEGACY_VERSION -> envelope.contact.run { copy(localPhotoHash = localPhotoHash ?: PhotoHash.UNKNOWN) }
            else -> null
        }
    }
}
