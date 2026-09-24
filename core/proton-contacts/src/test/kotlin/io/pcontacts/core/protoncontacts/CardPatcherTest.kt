// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.protoncontacts

import ezvcard.Ezvcard
import ezvcard.VCard
import ezvcard.VCardVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An update patches the server's cards (ADR-0017 §2, Choice 2C): what
 * the app does not own must come back exactly as it went in.
 */
class CardPatcherTest {

    private val signedCard = """
        BEGIN:VCARD
        VERSION:4.0
        PRODID:-//ProtonMail//ProtonMail vCard 1.0.0//EN
        FN:Alice Example
        UID:proton-web-uid
        item1.EMAIL;PREF=1:alice@example.com
        item1.KEY:data:application/pgp-keys;base64,AAAA
        item1.X-PM-ENCRYPT:true
        item1.X-PM-SIGN:true
        item2.EMAIL:alice@work.example
        END:VCARD
    """.trimIndent()

    private val encryptedCard = """
        BEGIN:VCARD
        VERSION:4.0
        N:Example;Alice;Marie,Jane;Dr.;PhD
        TEL;TYPE=cell,voice;PREF=1:+15550001
        TEL;TYPE=home:+15550002
        ADR;TYPE=home:;;1 Main St;Springfield;IL;62701;USA
        ORG:Acme;R&D;Platform
        TITLE:Engineer
        TITLE:Lead
        NOTE:Keep me
        BDAY:19800101
        URL:https://alice.example
        NICKNAME:Ali
        X-ABLabel:Custom
        PHOTO;MEDIATYPE=image/jpeg:data:image/jpeg;base64,/9j/4AAQ
        END:VCARD
    """.trimIndent()

    private val clearCard = """
        BEGIN:VCARD
        VERSION:4.0
        CATEGORIES:Family,Work
        END:VCARD
    """.trimIndent()

    private fun carrier() = listOf(
        DecryptedCard(CardType.SIGNED, signedCard, verified = true),
        DecryptedCard(CardType.ENCRYPTED_AND_SIGNED, encryptedCard, verified = true),
        DecryptedCard(CardType.CLEAR_TEXT, clearCard, verified = true)
    )

    private fun rendered(patch: ContactPatch, cards: List<DecryptedCard> = carrier()): Map<CardType, VCard> {
        val patcher = CardPatcher(cards, fallbackUid = "urn:uuid:fallback")
        patcher.apply(patch)
        return patcher.render().associate { (type, text) -> type to Ezvcard.parse(text).first() }
    }

    @Test fun an_unrelated_edit_leaves_everything_else_untouched() {
        val out = rendered(ContactPatch(notes = ContactPatch.Change(listOf("Keep me", "New note"))))

        assertEquals(setOf(CardType.SIGNED, CardType.ENCRYPTED_AND_SIGNED, CardType.CLEAR_TEXT), out.keys)
        val signed = out.getValue(CardType.SIGNED)
        assertEquals("proton-web-uid", signed.uid.value)
        assertEquals("item1", signed.emails[0].group)
        assertEquals(1, signed.emails[0].pref)
        assertEquals(1, signed.keys.size)
        assertEquals("item1", signed.keys[0].group)
        assertEquals("true", signed.getExtendedProperty("X-PM-ENCRYPT").value)
        assertNotNull(signed.getProperty(ezvcard.property.ProductId::class.java))

        val enc = out.getValue(CardType.ENCRYPTED_AND_SIGNED)
        assertEquals(listOf("Marie", "Jane"), enc.structuredName.additionalNames)
        assertEquals(setOf("cell", "voice"), enc.telephoneNumbers[0].types.map { it.value }.toSet())
        assertEquals(listOf("Acme", "R&D", "Platform"), enc.organization.values)
        assertEquals(listOf("Engineer", "Lead"), enc.titles.map { it.value })
        assertEquals(listOf("Keep me", "New note"), enc.notes.map { it.value })
        assertNotNull(enc.birthday)
        assertEquals("https://alice.example", enc.urls[0].value)
        assertEquals("Ali", enc.nickname.values[0])
        assertEquals("Custom", enc.getExtendedProperty("X-ABLabel").value)
        assertEquals("image/jpeg", enc.photos[0].contentType.mediaType)

        assertEquals(listOf("Family", "Work"), out.getValue(CardType.CLEAR_TEXT).categories.values)
    }

    @Test fun birthday_is_replaced_in_its_card_and_anniversary_added_to_the_encrypted_one() {
        val out = rendered(
            ContactPatch(
                birthday = ContactPatch.Change("1990-03-12"),
                anniversary = ContactPatch.Change("2015-06-20")
            )
        )
        val enc = out.getValue(CardType.ENCRYPTED_AND_SIGNED)
        assertEquals(1, enc.birthdays.size)
        assertEquals(java.time.LocalDate.of(1990, 3, 12), enc.birthday.date)
        assertEquals(java.time.LocalDate.of(2015, 6, 20), enc.anniversary.date)
        assertTrue(out.getValue(CardType.SIGNED).birthdays.isEmpty())

        val removed = rendered(ContactPatch(birthday = ContactPatch.Change(null)))
        assertTrue(removed.getValue(CardType.ENCRYPTED_AND_SIGNED).birthdays.isEmpty())
    }

    @Test fun nicknames_and_websites_are_patched_value_by_value() {
        val out = rendered(
            ContactPatch(
                nicknames = ContactPatch.Change(listOf("Lissy")),
                websites = ContactPatch.Change(listOf("https://alice.example", "https://blog.alice.example"))
            )
        )
        val enc = out.getValue(CardType.ENCRYPTED_AND_SIGNED)
        assertEquals(listOf("Lissy"), enc.nicknames.flatMap { it.values })
        assertEquals(listOf("https://alice.example", "https://blog.alice.example"), enc.urls.map { it.value })
    }

    @Test fun an_added_email_lands_in_the_signed_card_with_a_fresh_group() {
        val out = rendered(
            ContactPatch(emails = ContactPatch.ListPatch(added = listOf(DecryptedEmail("third@example.com"))))
        )

        val signed = out.getValue(CardType.SIGNED)
        val added = signed.emails.single { it.value == "third@example.com" }
        assertEquals("item3", added.group)
        assertNull(added.pref)
        assertTrue(out.getValue(CardType.ENCRYPTED_AND_SIGNED).emails.isEmpty())
    }

    @Test fun removing_an_email_removes_its_key_and_x_pm_group_mates() {
        val out = rendered(
            ContactPatch(emails = ContactPatch.ListPatch(removed = listOf(DecryptedEmail("alice@example.com"))))
        )

        val signed = out.getValue(CardType.SIGNED)
        assertEquals(listOf("alice@work.example"), signed.emails.map { it.value })
        assertTrue(signed.keys.isEmpty())
        assertNull(signed.getExtendedProperty("X-PM-ENCRYPT"))
        assertNull(signed.getExtendedProperty("X-PM-SIGN"))
    }

    @Test fun pref_is_written_only_when_primary_changed() {
        val untouched = rendered(ContactPatch())
        assertNull(untouched.getValue(CardType.SIGNED).emails[1].pref)

        val moved = rendered(
            ContactPatch(
                emails = ContactPatch.ListPatch(
                    modified = listOf(
                        DecryptedEmail("alice@example.com", isPrimary = false),
                        DecryptedEmail("alice@work.example", isPrimary = true)
                    )
                )
            )
        )
        val emails = moved.getValue(CardType.SIGNED).emails.associateBy { it.value }
        assertNull(emails.getValue("alice@example.com").pref)
        assertEquals(1, emails.getValue("alice@work.example").pref)
    }

    @Test fun modifying_a_phone_keeps_its_foreign_type_tokens() {
        val out = rendered(
            ContactPatch(
                phones = ContactPatch.ListPatch(modified = listOf(DecryptedPhone("+15550001", listOf("work"))))
            )
        )

        val tel = out.getValue(CardType.ENCRYPTED_AND_SIGNED).telephoneNumbers.single { it.text == "+15550001" }
        assertEquals(setOf("work", "voice"), tel.types.map { it.value }.toSet())
        assertNull(tel.pref)
    }

    @Test fun name_and_organization_are_patched_component_wise() {
        val out = rendered(
            ContactPatch(
                structuredName = ContactPatch.Change(
                    // The projection keeps one element per list; the patch carries what it saw.
                    DecryptedStructuredName(
                        given = "Alicia",
                        family = "Example",
                        additionalNames = listOf("May"),
                        prefixes = listOf("Dr."),
                        suffixes = listOf("PhD")
                    )
                ),
                organization = ContactPatch.Change(
                    DecryptedOrganization(company = "Acme Corp", department = "R&D", title = "Principal")
                )
            )
        )

        val enc = out.getValue(CardType.ENCRYPTED_AND_SIGNED)
        assertEquals("Alicia", enc.structuredName.given)
        assertEquals(listOf("May", "Jane"), enc.structuredName.additionalNames)
        assertEquals(listOf("Dr."), enc.structuredName.prefixes)
        assertEquals(listOf("Acme Corp", "R&D", "Platform"), enc.organization.values)
        assertEquals(listOf("Principal", "Lead"), enc.titles.map { it.value })
    }

    @Test fun photo_is_replaced_in_place_and_removed_on_null() {
        val replaced = rendered(
            ContactPatch(photo = ContactPatch.Change(DecryptedPhoto(byteArrayOf(1, 2, 3), "image/png")))
        )
        val photo = replaced.getValue(CardType.ENCRYPTED_AND_SIGNED).photos.single()
        assertEquals(listOf<Byte>(1, 2, 3), photo.data.toList())
        assertEquals("image/png", photo.contentType.mediaType)

        val removed = rendered(ContactPatch(photo = ContactPatch.Change(null)))
        assertTrue(removed.getValue(CardType.ENCRYPTED_AND_SIGNED).photos.isEmpty())
    }

    @Test fun legacy_email_in_the_encrypted_card_moves_to_the_signed_card_and_stray_uid_is_dropped() {
        val legacyEncrypted = """
            BEGIN:VCARD
            VERSION:4.0
            UID:stray
            EMAIL:legacy@example.com
            TEL:+1
            END:VCARD
        """.trimIndent()
        val out = rendered(
            ContactPatch(),
            listOf(
                DecryptedCard(CardType.SIGNED, "BEGIN:VCARD\nVERSION:4.0\nFN:L\nUID:real\nEND:VCARD", verified = true),
                DecryptedCard(CardType.ENCRYPTED_AND_SIGNED, legacyEncrypted, verified = true)
            )
        )

        val movedEmail = out.getValue(CardType.SIGNED).emails.single()
        assertEquals("legacy@example.com", movedEmail.value)
        assertEquals("a moved email gets the group Proton requires", "item1", movedEmail.group)
        assertEquals("real", out.getValue(CardType.SIGNED).uid.value)
        val enc = out.getValue(CardType.ENCRYPTED_AND_SIGNED)
        assertNull(enc.uid)
        assertTrue(enc.emails.isEmpty())
        assertEquals("+1", enc.telephoneNumbers[0].text)
    }

    @Test fun a_server_made_clear_text_carrier_is_rebuilt_into_the_cards_proton_accepts() {
        // What an auto-saved contact looks like: one CLEAR_TEXT card, no signature anywhere.
        val autoSaved = """
            BEGIN:VCARD
            VERSION:4.0
            FN:auto@example.com
            UID:proton-auto
            item1.EMAIL;PREF=1:auto@example.com
            CATEGORIES:Auto
            END:VCARD
        """.trimIndent()
        val out = rendered(
            ContactPatch(
                fullName = ContactPatch.Change("Auto Saved"),
                structuredName = ContactPatch.Change(DecryptedStructuredName(given = "Auto", family = "Saved"))
            ),
            listOf(DecryptedCard(CardType.CLEAR_TEXT, autoSaved, verified = true))
        )

        val signed = out.getValue(CardType.SIGNED)
        assertEquals("a card made here is 4.0, as Proton requires", VCardVersion.V4_0, signed.version)
        assertEquals(VCardVersion.V4_0, out.getValue(CardType.ENCRYPTED_AND_SIGNED).version)
        assertEquals("PREF survives as PREF, not as a 3.0 TYPE=pref", 1, signed.emails.single().pref)
        assertEquals("Auto Saved", signed.formattedName.value)
        assertEquals("the server's UID is kept, not minted anew", "proton-auto", signed.uid.value)
        assertEquals("auto@example.com", signed.emails.single().value)
        assertEquals("item1", signed.emails.single().group)
        val clear = out.getValue(CardType.CLEAR_TEXT)
        assertNull(clear.formattedName)
        assertTrue(clear.emails.isEmpty())
        assertEquals(listOf("Auto"), clear.categories.values)
        assertEquals("Saved", out.getValue(CardType.ENCRYPTED_AND_SIGNED).structuredName.family)
    }

    @Test fun shape_names_types_groups_and_parameters_but_no_values() {
        val shape = CardShape.of(CardType.SIGNED, signedCard)
        assertTrue(shape, shape.startsWith("SIGNED[") && shape.contains("item1.EMAIL;PREF") && shape.contains("UID"))
        assertTrue("no value leaks", !shape.contains("alice") && !shape.contains("proton-web-uid"))
    }

    @Test fun a_vcard_3_carrier_keeps_its_version_only_properties() {
        val v3 = """
            BEGIN:VCARD
            VERSION:3.0
            N:Old;Legacy;;;
            TEL;TYPE=pref,home:+2
            LABEL;TYPE=home:Some label
            END:VCARD
        """.trimIndent()
        val out = rendered(
            ContactPatch(notes = ContactPatch.Change(listOf("n"))),
            listOf(
                DecryptedCard(CardType.SIGNED, "BEGIN:VCARD\nVERSION:4.0\nFN:L\nUID:u\nEND:VCARD", verified = true),
                DecryptedCard(CardType.ENCRYPTED_AND_SIGNED, v3, verified = true)
            )
        )

        val enc = out.getValue(CardType.ENCRYPTED_AND_SIGNED)
        assertEquals(setOf("pref", "home"), enc.telephoneNumbers[0].types.map { it.value }.toSet())
        assertEquals("Some label", enc.getProperty(ezvcard.property.Label::class.java).value)
        assertEquals(listOf("n"), enc.notes.map { it.value })
    }

    @Test fun a_missing_signed_uid_falls_back_and_an_emptied_card_is_dropped() {
        val out = rendered(
            ContactPatch(notes = ContactPatch.Change(emptyList())),
            listOf(
                DecryptedCard(CardType.SIGNED, "BEGIN:VCARD\nVERSION:4.0\nFN:L\nEND:VCARD", verified = true),
                DecryptedCard(
                    CardType.ENCRYPTED_AND_SIGNED,
                    "BEGIN:VCARD\nVERSION:4.0\nNOTE:only\nEND:VCARD",
                    verified = true
                )
            )
        )

        assertEquals("urn:uuid:fallback", out.getValue(CardType.SIGNED).uid.value)
        assertEquals(setOf(CardType.SIGNED), out.keys)
    }
}
