// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.crypto.openpgp

/**
 * The four OpenPGP operations the rest of pcontacts ever needs.
 * Matches the surface `@protontech/crypto` exposes via `CryptoProxy`
 * (encryptMessage, signMessage, decryptMessage, verifyMessage) so that
 * porting Proton's contact decrypt/encrypt orchestration is a one-to-one
 * mapping in the `:core:proton-contacts` module.
 *
 * Verification markers:
 *   `[V]` algorithm choices (OpenPGP, detached signatures, canonical text)
 *         confirmed from `packages/shared/lib/contacts/{encrypt,decrypt}.ts`.
 *   `[A]` exact canonicalization rules — see `TextCanonicalization` doc.
 */
interface OpenPgpService {

    /** Encrypts `plaintext` to `encryptionKeys` and signs with `signingKey` (detached). */
    fun encryptAndSignDetached(
        plaintext: ByteArray,
        encryptionKeys: List<PgpPublicKeyHandle>,
        signingKey: PgpPrivateKeyHandle
    ): EncryptedSignedResult

    /** Produces an ASCII-armored detached signature over `plaintext`. */
    fun signDetached(
        plaintext: ByteArray,
        signingKey: PgpPrivateKeyHandle,
        canonicalText: Boolean = true,
        stripTrailingSpaces: Boolean = true
    ): String

    /**
     * Decrypts `armoredMessage` using the first matching key from
     * `decryptionKeys`. When `detachedSignature` is non-null,
     * also verifies it against `verificationKeys`.
     */
    fun decryptAndVerify(
        armoredMessage: String,
        detachedSignature: String?,
        decryptionKeys: List<PgpPrivateKeyHandle>,
        verificationKeys: List<PgpPublicKeyHandle>
    ): VerifiedDecryptResult

    /** Verifies an ASCII-armored detached signature over `plaintext`. */
    fun verifyDetached(
        plaintext: ByteArray,
        armoredSignature: String,
        verificationKeys: List<PgpPublicKeyHandle>,
        canonicalText: Boolean = true,
        stripTrailingSpaces: Boolean = true
    ): VerificationStatus
}
