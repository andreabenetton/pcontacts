// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.protoncontacts

/**
 * Function-type seam between `ContactDecrypter` and the actual OpenPGP
 * implementation. Production wires this to :core:crypto's
 * `OpenPgpService` + the unlocked user/address keys; tests pass a
 * canned lambda and never touch real crypto.
 *
 * Why a sealed request instead of a plain pair of (data, signature?):
 * each card type drives a different OpenPGP operation, and the
 * dispatcher knows which one to call. Bundling the request kind into
 * the sealed class keeps the production adapter exhaustive (the
 * `when` over CardCryptoRequest must cover every variant).
 */
sealed interface CardCryptoRequest {
    /** SIGNED card — `data` is plaintext, verify the detached `signature` over it. */
    data class VerifyOnly(val data: String, val signature: String) : CardCryptoRequest

    /** ENCRYPTED card — `armored` is the OpenPGP message; no signature path. */
    data class DecryptOnly(val armored: String) : CardCryptoRequest

    /** ENCRYPTED_AND_SIGNED card — decrypt + verify detached signature in one shot. */
    data class DecryptAndVerify(val armored: String, val signature: String) : CardCryptoRequest
}

/**
 * Outcome the dispatcher hands back. `plaintext` is the readable vCard
 * fragment regardless of the source card type; `verified` distinguishes
 * "signature checked out" from "we trust the bytes but no integrity
 * proof was provided / the proof failed".
 */
data class CardCryptoOutcome(
    val plaintext: String,
    val verified: Boolean
)

typealias CardCryptoOp = (CardCryptoRequest) -> CardCryptoOutcome
