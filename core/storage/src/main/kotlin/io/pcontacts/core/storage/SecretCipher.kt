// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.storage

import java.security.KeyStoreException

/**
 * The one AEAD every secret and the merge base go through (ADR-0009,
 * ADR-0018). Production is [KeystoreAesGcmKek]; tests inject a JVM
 * cipher because Robolectric has no AndroidKeyStore provider.
 */
interface SecretCipher {

    /** Seals [plaintext]; the result carries its own IV. */
    fun wrap(plaintext: ByteArray): ByteArray

    /**
     * Opens [wrapped]. Throws [KekMissingException] when the key is
     * gone (after logout) and never provisions a replacement, so stale
     * ciphertext can only ever read as absent.
     */
    fun unwrap(wrapped: ByteArray): ByteArray

    /** Deletes the key and verifies it is gone; throws when it is not. */
    fun delete()
}

/** The Keystore alias is absent: the value was written under a key that logout deleted. */
class KekMissingException(alias: String) : KeyStoreException("Keystore alias absent: $alias")
