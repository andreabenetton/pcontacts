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

    @Test fun adr_surfaces_every_component_with_types_and_isPrimary() {
        val out = merger.merge(
            protonContactId = "c1",
            decrypted = listOf(card(CardType.ENCRYPTED_AND_SIGNED, """
                BEGIN:VCARD
                VERSION:4.0
                FN:Alice
                EMAIL:alice@proton.me
                ADR;TYPE=home;PREF=1:PO Box 5;Suite 3;100 Main St;Springfield;IL;62704;USA
                ADR;TYPE=work:;;200 Office Way;Chicago;IL;60601;USA
                END:VCARD
            """.trimIndent()))
        )
        assertEquals(2, out.addresses.size)
        val home = out.addresses.single { it.types.contains("home") }
        assertEquals("PO Box 5", home.poBox)
        assertEquals("Suite 3", home.extendedAddress)
        assertEquals("100 Main St", home.street)
        assertEquals("Springfield", home.locality)
        assertEquals("IL", home.region)
        assertEquals("62704", home.postalCode)
        assertEquals("USA", home.country)
        assertTrue("PREF=1 ADR must surface as primary", home.isPrimary)
    }

    @Test fun adr_drops_entries_with_no_usable_component() {
        val out = merger.merge(
            protonContactId = "c1",
            decrypted = listOf(card(CardType.ENCRYPTED_AND_SIGNED, """
                BEGIN:VCARD
                VERSION:4.0
                FN:Alice
                EMAIL:alice@proton.me
                ADR:;;;;;;
                END:VCARD
            """.trimIndent()))
        )
        assertEquals(emptyList<Any>(), out.addresses)
    }

    @Test fun org_company_department_and_first_title_surface() {
        val out = merger.merge(
            protonContactId = "c1",
            decrypted = listOf(card(CardType.ENCRYPTED_AND_SIGNED, """
                BEGIN:VCARD
                VERSION:4.0
                FN:Alice
                EMAIL:alice@proton.me
                ORG:Acme Inc.;R&D
                TITLE:Principal Engineer
                TITLE:Founding member
                END:VCARD
            """.trimIndent()))
        )
        val o = out.organization
        assertNotNull(o)
        assertEquals("Acme Inc.", o!!.company)
        assertEquals("R&D", o.department)
        assertEquals("Principal Engineer", o.title)
    }

    @Test fun organization_is_null_when_neither_org_nor_title_present() {
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
        assertNull(out.organization)
    }

    @Test fun notes_collect_every_NOTE_property_dropping_blanks() {
        val out = merger.merge(
            protonContactId = "c1",
            decrypted = listOf(card(CardType.ENCRYPTED_AND_SIGNED, """
                BEGIN:VCARD
                VERSION:4.0
                FN:Alice
                EMAIL:alice@proton.me
                NOTE:First note
                NOTE:Second note
                END:VCARD
            """.trimIndent()))
        )
        assertEquals(listOf("First note", "Second note"), out.notes)
    }

    @Test fun impp_parses_scheme_as_protocol_and_rest_as_handle() {
        val out = merger.merge(
            protonContactId = "c1",
            decrypted = listOf(card(CardType.ENCRYPTED_AND_SIGNED, """
                BEGIN:VCARD
                VERSION:4.0
                FN:Alice
                EMAIL:alice@proton.me
                IMPP:xmpp:alice@chat.example
                IMPP:skype:alice.live
                END:VCARD
            """.trimIndent()))
        )
        assertEquals(2, out.imAccounts.size)
        val xmpp = out.imAccounts.single { it.protocol == "xmpp" }
        assertEquals("alice@chat.example", xmpp.handle)
        val skype = out.imAccounts.single { it.protocol == "skype" }
        assertEquals("alice.live", skype.handle)
    }

    @Test fun inline_photo_surfaces_as_byte_data_with_mime_type() {
        // Tiny 1x1 transparent PNG so the test stays self-contained.
        val pngBytes = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
            0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15.toByte(), 0xC4.toByte(),
            0x89.toByte(), 0x00, 0x00, 0x00, 0x0D, 0x49, 0x44, 0x41,
            0x54, 0x78, 0x9C.toByte(), 0x62, 0x00, 0x01, 0x00, 0x00,
            0x05, 0x00, 0x01, 0x0D, 0x0A, 0x2D, 0xB4.toByte(), 0x00,
            0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(),
            0x42, 0x60, 0x82.toByte()
        )
        val photoB64 = java.util.Base64.getEncoder().encodeToString(pngBytes)
        val out = merger.merge(
            protonContactId = "c1",
            decrypted = listOf(card(CardType.ENCRYPTED_AND_SIGNED, """
                BEGIN:VCARD
                VERSION:4.0
                FN:Alice
                EMAIL:alice@proton.me
                PHOTO:data:image/png;base64,$photoB64
                END:VCARD
            """.trimIndent()))
        )
        val photo = out.photo
        assertNotNull(photo)
        assertTrue("photo bytes must survive the data: URI parse",
            pngBytes.contentEquals(photo!!.data))
        assertEquals("image/png", photo.mimeType)
    }

    @Test fun photo_is_null_when_no_inline_PHOTO_present() {
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
        assertNull(out.photo)
    }

    private fun card(type: CardType, plaintext: String, verified: Boolean = true) =
        DecryptedCard(originalType = type, plaintext = plaintext, verified = verified)
}
