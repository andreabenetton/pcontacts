// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.sync.contacts.decrypt

import io.pcontacts.core.crypto.openpgp.OpenPgpService
import io.pcontacts.core.crypto.openpgp.PgpPrivateKeyHandle
import io.pcontacts.core.crypto.openpgp.PgpPublicKeyHandle
import io.pcontacts.core.crypto.openpgp.VerificationStatus
import io.pcontacts.core.protoncontacts.CardCryptoOp
import io.pcontacts.core.protoncontacts.CardCryptoOutcome
import io.pcontacts.core.protoncontacts.CardCryptoRequest

/**
 * Adapts :core:crypto's `OpenPgpService` (PGP-key-handle-aware) to
 * :core:proton-contacts's `CardCryptoOp` (keyless function-type seam).
 * Lives in :core:sync because it's the integration point both sides
 * pull through — neither lower module needs to know about the other.
 *
 * Per-card semantics mirror `ContactDecrypter`'s contract:
 *   - VerifyOnly        → openPgp.verifyDetached over the inline plaintext
 *   - DecryptOnly       → openPgp.decryptAndVerify with no signature
 *   - DecryptAndVerify  → openPgp.decryptAndVerify with detached signature
 *
 * The same `decryptionKey` + `verificationKeys` are used for every
 * card from a contact — Proton issues one user-key pair that handles
 * both signing/verification and encryption/decryption for the user's
 * own contacts ([V] in the web-client key flag set; key-rotation
 * support lands with the complete version).
 */
object OpenPgpCardCryptoOp {

    /**
     * Closes over `openPgp` + the unlocked keys to produce a stateless
     * `CardCryptoOp` ready to hand to a `ContactDecrypter`. The
     * returned lambda holds references to live PGP key material; per
     * ADR-0009 the caller scopes it to a single sync run and lets it
     * go out of scope when done.
     */
    fun build(
        openPgp: OpenPgpService,
        decryptionKey: PgpPrivateKeyHandle,
        verificationKeys: List<PgpPublicKeyHandle>
    ): CardCryptoOp = { request ->
        when (request) {
            is CardCryptoRequest.VerifyOnly -> {
                val status = openPgp.verifyDetached(
                    plaintext = request.data.toByteArray(Charsets.UTF_8),
                    armoredSignature = request.signature,
                    verificationKeys = verificationKeys
                )
                CardCryptoOutcome(
                    plaintext = request.data,
                    verified = status == VerificationStatus.SIGNED_AND_VALID
                )
            }
            is CardCryptoRequest.DecryptOnly -> {
                val result = openPgp.decryptAndVerify(
                    armoredMessage = request.armored,
                    detachedSignature = null,
                    decryptionKey = decryptionKey,
                    verificationKeys = emptyList()
                )
                // ENCRYPTED-only cards have no signature path; the
                // dispatcher upstream marks them verified=true by virtue
                // of having decrypted under the user's own key.
                CardCryptoOutcome(
                    plaintext = String(result.plaintext, Charsets.UTF_8),
                    verified = true
                )
            }
            is CardCryptoRequest.DecryptAndVerify -> {
                val result = openPgp.decryptAndVerify(
                    armoredMessage = request.armored,
                    detachedSignature = request.signature,
                    decryptionKey = decryptionKey,
                    verificationKeys = verificationKeys
                )
                CardCryptoOutcome(
                    plaintext = String(result.plaintext, Charsets.UTF_8),
                    verified = result.verificationStatus == VerificationStatus.SIGNED_AND_VALID
                )
            }
        }
    }
}
