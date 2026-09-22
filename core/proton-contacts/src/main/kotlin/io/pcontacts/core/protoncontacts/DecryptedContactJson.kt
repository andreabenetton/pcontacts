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

    const val VERSION = 1

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
        return envelope.contact.takeIf { envelope.v == VERSION }
    }
}
