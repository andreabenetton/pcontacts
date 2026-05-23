// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.protoncontacts

import io.pcontacts.core.proton.api.contacts.ContactCardDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dispatch tests with a canned crypto lambda. Validates the four wire
 * Card types route to the right CardCryptoRequest variant and that the
 * verified/plaintext outcomes propagate.
 */
class ContactDecrypterTest {

    @Test fun clear_text_card_passes_data_through_verified_true_without_crypto() {
        var called = false
        val decrypter = ContactDecrypter(cryptoOp = { called = true; error("must not be called") })

        val out = decrypter.decryptOne(ContactCardDto(type = 0, data = "BEGIN:VCARD..."))

        assertEquals(DecryptedCard(CardType.CLEAR_TEXT, "BEGIN:VCARD...", verified = true), out)
        assertFalse("CLEAR_TEXT must not invoke the crypto op", called)
    }

    @Test fun signed_card_calls_verify_only_and_propagates_verified_flag() {
        val seen = mutableListOf<CardCryptoRequest>()
        val decrypter = ContactDecrypter(cryptoOp = { req ->
            seen += req
            CardCryptoOutcome(plaintext = "ignored — VerifyOnly preserves input data", verified = true)
        })

        val out = decrypter.decryptOne(
            ContactCardDto(type = 2, data = "FN:Alice", signature = "-----BEGIN PGP SIGNATURE-----...")
        )!!

        assertEquals(CardType.SIGNED, out.originalType)
        assertTrue(out.verified)
        assertEquals(1, seen.size)
        val req = seen.single() as CardCryptoRequest.VerifyOnly
        assertEquals("FN:Alice", req.data)
        assertTrue(req.signature.startsWith("-----BEGIN PGP SIGNATURE-----"))
    }

    @Test fun signed_card_with_failing_signature_keeps_plaintext_but_marks_unverified() {
        val decrypter = ContactDecrypter(cryptoOp = { _ ->
            CardCryptoOutcome(plaintext = "FN:Alice", verified = false)
        })

        val out = decrypter.decryptOne(
            ContactCardDto(type = 2, data = "FN:Alice", signature = "tampered")
        )!!

        // Per ADR-0007 / Plan §10.1: retain the data, mark isVerified=false.
        assertEquals("FN:Alice", out.plaintext)
        assertFalse(out.verified)
    }

    @Test fun signed_card_missing_signature_marks_unverified_and_skips_crypto() {
        var called = false
        val decrypter = ContactDecrypter(cryptoOp = { called = true; error("must not be called") })

        val out = decrypter.decryptOne(
            ContactCardDto(type = 2, data = "FN:Alice", signature = null)
        )!!

        assertEquals("FN:Alice", out.plaintext)
        assertFalse(out.verified)
        assertFalse("missing-signature path must not invoke crypto op", called)
    }

    @Test fun encrypted_card_uses_decrypt_only_and_is_verified_true_by_default() {
        val decrypter = ContactDecrypter(cryptoOp = { req ->
            when (req) {
                is CardCryptoRequest.DecryptOnly ->
                    CardCryptoOutcome(plaintext = "TEL:+1 555 0000", verified = true)
                else -> error("expected DecryptOnly, got $req")
            }
        })

        val out = decrypter.decryptOne(
            ContactCardDto(type = 1, data = "-----BEGIN PGP MESSAGE-----...", signature = null)
        )!!

        assertEquals(CardType.ENCRYPTED, out.originalType)
        assertEquals("TEL:+1 555 0000", out.plaintext)
        assertTrue("ENCRYPTED-only cards have no signature path; trust the decrypt", out.verified)
    }

    @Test fun encrypted_and_signed_card_uses_decrypt_and_verify() {
        val decrypter = ContactDecrypter(cryptoOp = { req ->
            when (req) {
                is CardCryptoRequest.DecryptAndVerify -> {
                    assertEquals("-----BEGIN PGP MESSAGE-----...", req.armored)
                    assertEquals("-----BEGIN PGP SIGNATURE-----...", req.signature)
                    CardCryptoOutcome(plaintext = "N:Doe;Alice;;;", verified = true)
                }
                else -> error("expected DecryptAndVerify, got $req")
            }
        })

        val out = decrypter.decryptOne(
            ContactCardDto(
                type = 3,
                data = "-----BEGIN PGP MESSAGE-----...",
                signature = "-----BEGIN PGP SIGNATURE-----..."
            )
        )!!

        assertEquals(CardType.ENCRYPTED_AND_SIGNED, out.originalType)
        assertEquals("N:Doe;Alice;;;", out.plaintext)
        assertTrue(out.verified)
    }

    @Test fun encrypted_and_signed_card_missing_signature_falls_back_to_decrypt_only_unverified() {
        val decrypter = ContactDecrypter(cryptoOp = { req ->
            assertTrue(req is CardCryptoRequest.DecryptOnly)
            CardCryptoOutcome(plaintext = "TEL:+1 555 0000", verified = true)
        })

        val out = decrypter.decryptOne(
            ContactCardDto(type = 3, data = "-----BEGIN PGP MESSAGE-----...", signature = null)
        )!!

        assertEquals("TEL:+1 555 0000", out.plaintext)
        assertFalse("missing signature on ENCRYPTED_AND_SIGNED downgrades to unverified", out.verified)
    }

    @Test fun unknown_card_type_is_skipped_returning_null() {
        val decrypter = ContactDecrypter(cryptoOp = { error("must not be called for unknown type") })
        val out = decrypter.decryptOne(ContactCardDto(type = 99, data = "?"))
        assertNull(out)
    }

    @Test fun decryptCards_skips_unknown_types_and_preserves_order_of_known_ones() {
        val decrypter = ContactDecrypter(cryptoOp = { _ ->
            CardCryptoOutcome(plaintext = "FN:Alice", verified = true)
        })

        val out = decrypter.decryptCards(
            listOf(
                ContactCardDto(type = 0, data = "BEGIN:VCARD"),
                ContactCardDto(type = 99, data = "?"),                   // unknown — dropped
                ContactCardDto(type = 2, data = "FN:Alice", signature = "sig"),
            )
        )

        assertEquals(2, out.size)
        assertEquals(CardType.CLEAR_TEXT, out[0].originalType)
        assertEquals(CardType.SIGNED, out[1].originalType)
    }
}
