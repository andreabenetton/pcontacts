// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.protoncontacts

import io.pcontacts.core.proton.api.contacts.ContactCardDto
import io.pcontacts.core.proton.api.contacts.ContactDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end test of the ContactDto → DecryptedContact pipeline with a
 * canned crypto lambda. Validates the realistic case: a Contact whose
 * Cards mix CLEAR_TEXT (synthetic UID), SIGNED (real UID + FN +
 * primary email), and ENCRYPTED_AND_SIGNED (additional email).
 */
class ContactProcessorTest {

    @Test fun realistic_three_card_contact_decrypts_merges_and_verifies() {
        val signedPlaintext = """
            BEGIN:VCARD
            VERSION:4.0
            UID:urn:uuid:trusted-alice
            FN:Alice Doe
            EMAIL;PREF=1:alice@proton.me
            END:VCARD
        """.trimIndent()

        val encryptedSignedPlaintext = """
            BEGIN:VCARD
            VERSION:4.0
            EMAIL;TYPE=work:alice.work@proton.me
            END:VCARD
        """.trimIndent()

        val crypto: CardCryptoOp = { req ->
            when (req) {
                is CardCryptoRequest.VerifyOnly ->
                    CardCryptoOutcome(plaintext = req.data, verified = true)
                is CardCryptoRequest.DecryptAndVerify ->
                    CardCryptoOutcome(plaintext = encryptedSignedPlaintext, verified = true)
                is CardCryptoRequest.DecryptOnly ->
                    error("test contact uses no ENCRYPTED-only cards")
            }
        }

        val processor = ContactProcessor(ContactDecrypter(crypto))
        val contact = ContactDto(
            id = "c1",
            cards = listOf(
                // CLEAR_TEXT carries a stray UID; merger MUST discard it.
                ContactCardDto(type = 0, data = """
                    BEGIN:VCARD
                    VERSION:4.0
                    UID:urn:uuid:STRAY-FROM-CLEAR
                    END:VCARD
                """.trimIndent()),
                ContactCardDto(type = 2, data = signedPlaintext, signature = "sig-1"),
                ContactCardDto(type = 3, data = "armored", signature = "sig-2")
            )
        )

        val out = processor.process(contact)

        assertEquals("c1", out.protonContactId)
        assertEquals("urn:uuid:trusted-alice", out.protonUid)
        assertEquals("Alice Doe", out.fullName)
        assertEquals(
            setOf("alice@proton.me", "alice.work@proton.me"),
            out.emails.map { it.address }.toSet()
        )
        assertTrue(out.verified)
        assertEquals(3, out.cardCount)
        assertEquals(0, out.unverifiedCardCount)
    }

    @Test fun signature_failure_on_a_SIGNED_card_propagates_to_contact_verified_false() {
        val crypto: CardCryptoOp = { req ->
            when (req) {
                is CardCryptoRequest.VerifyOnly ->
                    CardCryptoOutcome(plaintext = req.data, verified = false)
                else -> CardCryptoOutcome("ignored", verified = true)
            }
        }
        val processor = ContactProcessor(ContactDecrypter(crypto))

        val out = processor.process(
            ContactDto(
                id = "c2",
                cards = listOf(
                    ContactCardDto(type = 2, data = """
                        BEGIN:VCARD
                        VERSION:4.0
                        FN:Alice
                        END:VCARD
                    """.trimIndent(), signature = "tampered")
                )
            )
        )
        assertFalse(out.verified)
        assertEquals(1, out.unverifiedCardCount)
    }
}
