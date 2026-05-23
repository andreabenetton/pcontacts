// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.protoncontacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real ez-vcard parsing against synthetic fragments. Validates the §10.3
 * merge semantics (UID discarded from non-SIGNED, properties accumulated)
 * and the contact-level verified flag derivation.
 */
class VCardMergerTest {

    private val merger = VCardMerger()

    @Test fun empty_card_list_yields_empty_contact() {
        val out = merger.merge(protonContactId = "c1", decrypted = emptyList())
        assertEquals(DecryptedContact.empty("c1"), out)
    }

    @Test fun single_clear_card_with_FN_only_populates_fullName() {
        val out = merger.merge(
            protonContactId = "c1",
            decrypted = listOf(card(CardType.CLEAR_TEXT, """
                BEGIN:VCARD
                VERSION:4.0
                FN:Alice
                END:VCARD
            """.trimIndent()))
        )
        assertEquals("Alice", out.fullName)
        assertEquals(emptyList<DecryptedEmail>(), out.emails)
        assertTrue(out.verified)
        assertEquals(0, out.unverifiedCardCount)
    }

    @Test fun fragments_merge_FN_from_signed_plus_EMAILs_from_encrypted_and_signed() {
        val out = merger.merge(
            protonContactId = "c1",
            decrypted = listOf(
                card(CardType.SIGNED, """
                    BEGIN:VCARD
                    VERSION:4.0
                    FN:Alice Doe
                    UID:urn:uuid:trusted-alice
                    EMAIL;PREF=1:alice@proton.me
                    END:VCARD
                """.trimIndent()),
                card(CardType.ENCRYPTED_AND_SIGNED, """
                    BEGIN:VCARD
                    VERSION:4.0
                    EMAIL;TYPE=work:alice.work@proton.me
                    END:VCARD
                """.trimIndent())
            )
        )
        assertEquals("Alice Doe", out.fullName)
        assertEquals("urn:uuid:trusted-alice", out.protonUid)
        assertEquals(2, out.emails.size)
        assertEquals(
            setOf("alice@proton.me", "alice.work@proton.me"),
            out.emails.map { it.address }.toSet()
        )
        val primary = out.emails.single { it.address == "alice@proton.me" }
        assertTrue("PREF=1 email must surface as primary", primary.isPrimary)
    }

    @Test fun uid_from_non_signed_card_is_discarded() {
        val out = merger.merge(
            protonContactId = "c1",
            decrypted = listOf(
                card(CardType.SIGNED, """
                    BEGIN:VCARD
                    VERSION:4.0
                    FN:Alice
                    UID:urn:uuid:trusted-alice
                    END:VCARD
                """.trimIndent()),
                card(CardType.ENCRYPTED_AND_SIGNED, """
                    BEGIN:VCARD
                    VERSION:4.0
                    UID:urn:uuid:ATTACKER-REBIND
                    EMAIL:alice@proton.me
                    END:VCARD
                """.trimIndent())
            )
        )
        // UID from the SIGNED card wins; the encrypted-and-signed card's UID is dropped.
        assertEquals("urn:uuid:trusted-alice", out.protonUid)
    }

    @Test fun uid_from_clear_text_card_is_discarded() {
        val out = merger.merge(
            protonContactId = "c1",
            decrypted = listOf(
                card(CardType.CLEAR_TEXT, """
                    BEGIN:VCARD
                    VERSION:4.0
                    UID:urn:uuid:tampered
                    END:VCARD
                """.trimIndent())
            )
        )
        assertNull("UID from CLEAR_TEXT must not bind a contact identity", out.protonUid)
    }

    @Test fun signature_failures_propagate_to_contact_level_unverified() {
        val out = merger.merge(
            protonContactId = "c1",
            decrypted = listOf(
                card(CardType.SIGNED, """
                    BEGIN:VCARD
                    VERSION:4.0
                    FN:Alice
                    END:VCARD
                """.trimIndent(), verified = false),
                card(CardType.CLEAR_TEXT, """
                    BEGIN:VCARD
                    VERSION:4.0
                    END:VCARD
                """.trimIndent(), verified = true)
            )
        )
        assertFalse("a SIGNED card with verified=false must downgrade the whole contact", out.verified)
        assertEquals(1, out.unverifiedCardCount)
    }

    @Test fun encrypted_only_card_verified_false_does_not_count_against_contact() {
        // ENCRYPTED cards have no signature requirement; their verified
        // flag is informational only.
        val out = merger.merge(
            protonContactId = "c1",
            decrypted = listOf(
                card(CardType.ENCRYPTED, """
                    BEGIN:VCARD
                    VERSION:4.0
                    FN:Alice
                    END:VCARD
                """.trimIndent(), verified = false)
            )
        )
        // The contact-level rule counts only SIGNED / ENCRYPTED_AND_SIGNED.
        assertTrue(out.verified)
        assertEquals(0, out.unverifiedCardCount)
    }

    @Test fun structured_name_drives_fullName_when_FN_is_missing() {
        val out = merger.merge(
            protonContactId = "c1",
            decrypted = listOf(
                card(CardType.ENCRYPTED_AND_SIGNED, """
                    BEGIN:VCARD
                    VERSION:4.0
                    N:Doe;Alice;Marie;;
                    END:VCARD
                """.trimIndent())
            )
        )
        assertNotNull(out.fullName)
        assertTrue("FN must include given name", out.fullName!!.contains("Alice"))
        assertTrue("FN must include family name", out.fullName!!.contains("Doe"))
    }

    @Test fun malformed_fragment_is_skipped_other_fragments_still_merge() {
        val out = merger.merge(
            protonContactId = "c1",
            decrypted = listOf(
                card(CardType.SIGNED, "this is not a vcard at all"),
                card(CardType.CLEAR_TEXT, """
                    BEGIN:VCARD
                    VERSION:4.0
                    FN:Alice
                    END:VCARD
                """.trimIndent())
            )
        )
        // The well-formed fragment still produces an FN.
        assertEquals("Alice", out.fullName)
    }

    @Test fun structured_name_pieces_surface_when_N_property_present() {
        val out = merger.merge(
            protonContactId = "c1",
            decrypted = listOf(card(CardType.ENCRYPTED_AND_SIGNED, """
                BEGIN:VCARD
                VERSION:4.0
                N:Doe;Alice;Marie,Jane;Dr;PhD
                FN:Alice Doe
                EMAIL:alice@proton.me
                END:VCARD
            """.trimIndent()))
        )
        val sn = out.structuredName
        assertNotNull(sn)
        assertEquals("Alice", sn!!.given)
        assertEquals("Doe", sn.family)
        // ez-vcard splits additionalNames on comma per RFC 6350.
        assertEquals(listOf("Marie", "Jane"), sn.additionalNames)
        assertEquals(listOf("Dr"), sn.prefixes)
        assertEquals(listOf("PhD"), sn.suffixes)
    }

    @Test fun structured_name_is_null_when_all_components_are_blank() {
        val out = merger.merge(
            protonContactId = "c1",
            decrypted = listOf(card(CardType.ENCRYPTED_AND_SIGNED, """
                BEGIN:VCARD
                VERSION:4.0
                N:;;;;
                FN:Alice
                EMAIL:alice@proton.me
                END:VCARD
            """.trimIndent()))
        )
        assertNull("an N with all-blank components must collapse to null", out.structuredName)
    }

    @Test fun phones_surface_with_types_and_isPrimary_from_TEL_PREF() {
        val out = merger.merge(
            protonContactId = "c1",
            decrypted = listOf(card(CardType.ENCRYPTED_AND_SIGNED, """
                BEGIN:VCARD
                VERSION:4.0
                FN:Alice
                EMAIL:alice@proton.me
                TEL;TYPE=home:+1 555 0100
                TEL;TYPE=cell;PREF=1:+1 555 0101
                TEL;TYPE=fax,work:+1 555 0102
                END:VCARD
            """.trimIndent()))
        )
        assertEquals(3, out.phones.size)
        val byNumber = out.phones.associateBy { it.number }
        assertEquals(setOf("home"), byNumber["+1 555 0100"]!!.types.toSet())
        assertTrue("cell + PREF=1 must be primary", byNumber["+1 555 0101"]!!.isPrimary)
        assertEquals(setOf("cell"), byNumber["+1 555 0101"]!!.types.toSet())
        assertEquals(setOf("fax", "work"), byNumber["+1 555 0102"]!!.types.toSet())
    }

    @Test fun phones_default_to_empty_list_when_no_TEL_property() {
        val out = merger.merge(
            protonContactId = "c1",
            decrypted = listOf(card(CardType.CLEAR_TEXT, """
                BEGIN:VCARD
                VERSION:4.0
                FN:Alice
                EMAIL:alice@proton.me
                END:VCARD
            """.trimIndent()))
        )
        assertEquals(emptyList<Any>(), out.phones)
    }

    private fun card(type: CardType, plaintext: String, verified: Boolean = true) =
        DecryptedCard(originalType = type, plaintext = plaintext, verified = verified)
}
