// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.protoncontacts

import io.pcontacts.core.logging.Logger
import io.pcontacts.core.logging.NoOpSink
import io.pcontacts.core.logging.RedactingLogger
import io.pcontacts.core.proton.api.contacts.ContactCardDto

/**
 * Per-card dispatch: routes a `ContactCardDto` into the right crypto
 * path (verify-only, decrypt-only, decrypt-and-verify) via the injected
 * `cryptoOp`. The actual OpenPGP machinery lives in :core:crypto;
 * separating the dispatch from the crypto keeps this module testable
 * without real keys and keeps :core:crypto's `internal` PGP handle
 * types from leaking across module boundaries.
 *
 * Verification policy mirrors Plan §10.1 and ADR-0007:
 *   - CLEAR_TEXT and ENCRYPTED have no signature requirement; verified=true.
 *   - SIGNED and ENCRYPTED_AND_SIGNED must produce a valid signature;
 *     a missing or failing signature yields verified=false but the
 *     plaintext is retained (a downgrade-resistant warning beats data loss).
 *   - An unknown wire `Type` is logged (count only) and skipped.
 */
class ContactDecrypter(
    private val cryptoOp: CardCryptoOp,
    private val logger: Logger = RedactingLogger(tag = "ContactDecrypt", sink = NoOpSink)
) {

    fun decryptCards(cards: List<ContactCardDto>): List<DecryptedCard> =
        cards.mapNotNull(::decryptOne)

    fun decryptOne(card: ContactCardDto): DecryptedCard? {
        val type = CardType.fromWire(card.type)
        if (type == null) {
            logger.warn { "unknown Card.Type=${card.type}; skipping" }
            return null
        }
        return when (type) {
            CardType.CLEAR_TEXT -> DecryptedCard(type, card.data, verified = true)

            CardType.SIGNED -> {
                val signature = card.signature
                if (signature.isNullOrBlank()) {
                    logger.warn { "SIGNED card missing Signature; retaining plaintext as unverified" }
                    DecryptedCard(type, card.data, verified = false)
                } else {
                    val outcome = cryptoOp(CardCryptoRequest.VerifyOnly(card.data, signature))
                    DecryptedCard(type, plaintext = outcome.plaintext, verified = outcome.verified)
                }
            }

            CardType.ENCRYPTED -> {
                val outcome = cryptoOp(CardCryptoRequest.DecryptOnly(card.data))
                // No detached signature path; the encryption itself
                // proves nothing about authorship — treat as verified by
                // virtue of having decrypted under the user's own key.
                DecryptedCard(type, plaintext = outcome.plaintext, verified = true)
            }

            CardType.ENCRYPTED_AND_SIGNED -> {
                val signature = card.signature
                if (signature.isNullOrBlank()) {
                    logger.warn { "ENCRYPTED_AND_SIGNED card missing Signature; decrypt without verify path" }
                    val outcome = cryptoOp(CardCryptoRequest.DecryptOnly(card.data))
                    DecryptedCard(type, plaintext = outcome.plaintext, verified = false)
                } else {
                    val outcome = cryptoOp(CardCryptoRequest.DecryptAndVerify(card.data, signature))
                    DecryptedCard(type, plaintext = outcome.plaintext, verified = outcome.verified)
                }
            }
        }
    }
}
