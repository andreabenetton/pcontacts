// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.crypto.openpgp

import java.security.Security
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openpgp.PGPPrivateKey
import org.bouncycastle.openpgp.PGPPublicKey

/**
 * Common types + Security-provider bootstrap for the OpenPGP layer.
 * Every entry point through `OpenPgpService` runs `ensureProvider()`
 * before touching BouncyCastle types, so callers don't need to think
 * about provider registration.
 */
internal object PgpProvider {
    @JvmStatic
    private val installed: Boolean = run {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
        true
    }
    fun ensureProvider() {
        // Touching this property forces the lazy init above.
        @Suppress("UNUSED_EXPRESSION") installed
    }
}

/** Wrapper so the BouncyCastle type doesn't leak across the service boundary. */
data class PgpPublicKeyHandle internal constructor(internal val raw: PGPPublicKey) {
    val keyIdHex: String get() = "%016X".format(raw.keyID)
}

data class PgpPrivateKeyHandle internal constructor(
    internal val raw: PGPPrivateKey,
    internal val pubKey: PGPPublicKey
) {
    val keyIdHex: String get() = "%016X".format(raw.keyID)
}

enum class VerificationStatus {
    /** Signature present and matches one of the verification keys. */
    SIGNED_AND_VALID,
    /** Signature present but verification failed (tampered or wrong key). */
    SIGNED_INVALID,
    /** No signature on the message / call site asked for an unsigned path. */
    NOT_SIGNED,
    /** Signature present, but no verification key was supplied or none matched the key ID. */
    SIGNED_NO_VERIFIER
}

data class EncryptedSignedResult(
    /** ASCII-armored OpenPGP message containing the encrypted payload. */
    val armoredMessage: String,
    /** ASCII-armored detached OpenPGP signature over the plaintext. */
    val armoredDetachedSignature: String
)

data class VerifiedDecryptResult(
    val plaintext: ByteArray,
    val verificationStatus: VerificationStatus
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VerifiedDecryptResult) return false
        return plaintext.contentEquals(other.plaintext) && verificationStatus == other.verificationStatus
    }
    override fun hashCode(): Int = 31 * plaintext.contentHashCode() + verificationStatus.hashCode()
}
