// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.protoncontacts

/**
 * Write-path counterpart to [CardCryptoOp]. Each variant maps to an
 * OpenPGP operation the production adapter wires to :core:crypto's
 * `OpenPgpService`; tests supply a pass-through or recording lambda.
 */
sealed interface CardEncryptRequest {
    /** SIGNED card — sign the plaintext, return it alongside the detached signature. */
    data class SignOnly(val plaintext: String) : CardEncryptRequest

    /** ENCRYPTED_AND_SIGNED card — encrypt + sign; return armored ciphertext + detached signature. */
    data class EncryptAndSign(val plaintext: String) : CardEncryptRequest
}

data class CardEncryptOutcome(
    /** Plaintext for SIGNED cards, armored ciphertext for ENCRYPTED_AND_SIGNED. */
    val data: String,
    /** Armored detached PGP signature. */
    val signature: String
)

typealias CardEncryptOp = (CardEncryptRequest) -> CardEncryptOutcome
