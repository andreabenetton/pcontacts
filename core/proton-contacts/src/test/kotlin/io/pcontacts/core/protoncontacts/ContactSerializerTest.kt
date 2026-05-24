// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.protoncontacts

import ezvcard.Ezvcard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactSerializerTest {

    private val passThrough: CardEncryptOp = { request ->
        when (request) {
            is CardEncryptRequest.SignOnly ->
                CardEncryptOutcome(data = request.plaintext, signature = "sig-signed")
            is CardEncryptRequest.EncryptAndSign ->
                CardEncryptOutcome(data = request.plaintext, signature = "sig-encrypted")
        }
    }

    private val serializer = ContactSerializer(encryptOp = passThrough)

    @Test fun serialize_produces_signed_card_with_FN_and_UID() {
        val contact = contact(fullName = "Alice Smith", protonUid = "uid-abc")
        val cards = serializer.serialize(contact)

        assertEquals(2, cards.size)
        val signed = cards[0]
        assertEquals(CardType.SIGNED.wireValue, signed.type)
        assertEquals("sig-signed", signed.signature)

        val vcard = Ezvcard.parse(signed.data).first()
        assertEquals("Alice Smith", vcard.formattedName.value)
        assertEquals("uid-abc", vcard.uid.value)
    }

    @Test fun serialize_produces_encrypted_and_signed_card_with_remaining_fields() {
        val contact = contact(
            fullName = "Alice",
            structuredName = DecryptedStructuredName(given = "Alice", family = "Smith"),
            emails = listOf(DecryptedEmail("alice@proton.me", listOf("home"), isPrimary = true)),
            phones = listOf(DecryptedPhone("555-1234", listOf("cell"), isPrimary = false))
        )
        val cards = serializer.serialize(contact)
        val encrypted = cards[1]
        assertEquals(CardType.ENCRYPTED_AND_SIGNED.wireValue, encrypted.type)
        assertEquals("sig-encrypted", encrypted.signature)

        val vcard = Ezvcard.parse(encrypted.data).first()
        assertNotNull(vcard.structuredName)
        assertEquals("Alice", vcard.structuredName.given)
        assertEquals("Smith", vcard.structuredName.family)
        assertEquals(1, vcard.emails.size)
        assertEquals("alice@proton.me", vcard.emails[0].value)
        assertEquals(1, vcard.telephoneNumbers.size)
        assertEquals("555-1234", vcard.telephoneNumbers[0].text)
        assertNull(vcard.formattedName)
    }

    @Test fun signed_card_does_not_contain_email_or_tel() {
        val contact = contact(
            fullName = "Bob",
            emails = listOf(DecryptedEmail("bob@proton.me", emptyList(), false)),
            phones = listOf(DecryptedPhone("555-0000", emptyList(), false))
        )
        val cards = serializer.serialize(contact)
        val signed = Ezvcard.parse(cards[0].data).first()
        assertTrue(signed.emails.isEmpty())
        assertTrue(signed.telephoneNumbers.isEmpty())
    }

    @Test fun serialize_assigns_uid_to_signed_card_only() {
        val contact = contact(fullName = "Carol", protonUid = "uid-xyz")
        val cards = serializer.serialize(contact)
        val signed = Ezvcard.parse(cards[0].data).first()
        val encrypted = Ezvcard.parse(cards[1].data).first()
        assertEquals("uid-xyz", signed.uid.value)
        assertNull(encrypted.uid)
    }

    @Test fun serialize_with_empty_fullName_uses_email_fallback() {
        val contact = contact(
            fullName = "",
            emails = listOf(DecryptedEmail("fallback@proton.me", emptyList(), false))
        )
        val cards = serializer.serialize(contact)
        val signed = Ezvcard.parse(cards[0].data).first()
        assertEquals("fallback@proton.me", signed.formattedName.value)
    }

    @Test fun serialize_with_no_fullName_or_email_uses_unknown() {
        val contact = contact(fullName = null, phones = listOf(DecryptedPhone("555", emptyList(), false)))
        val cards = serializer.serialize(contact)
        val signed = Ezvcard.parse(cards[0].data).first()
        assertEquals("Unknown", signed.formattedName.value)
    }

    @Test fun serialize_round_trips_through_decrypt() {
        val original = contact(
            fullName = "Alice Smith",
            protonUid = "uid-rt",
            structuredName = DecryptedStructuredName(
                given = "Alice", family = "Smith",
                additionalNames = listOf("Marie"),
                prefixes = listOf("Dr."),
                suffixes = listOf("Jr.")
            ),
            emails = listOf(
                DecryptedEmail("alice@proton.me", listOf("home"), isPrimary = true),
                DecryptedEmail("alice@work.com", listOf("work"), isPrimary = false)
            ),
            phones = listOf(
                DecryptedPhone("555-1234", listOf("cell"), isPrimary = true),
                DecryptedPhone("555-0000", listOf("home"), isPrimary = false)
            ),
            notes = listOf("A note")
        )

        val cards = serializer.serialize(original)

        val decryptedCards = cards.map { card ->
            DecryptedCard(
                originalType = CardType.fromWire(card.type)!!,
                plaintext = card.data,
                verified = true
            )
        }
        val roundTripped = VCardMerger().merge("contact-1", decryptedCards)

        assertEquals(original.fullName, roundTripped.fullName)
        assertEquals(original.protonUid, roundTripped.protonUid)
        assertEquals(original.structuredName?.given, roundTripped.structuredName?.given)
        assertEquals(original.structuredName?.family, roundTripped.structuredName?.family)
        assertEquals(original.structuredName?.additionalNames, roundTripped.structuredName?.additionalNames)
        assertEquals(original.structuredName?.prefixes, roundTripped.structuredName?.prefixes)
        assertEquals(original.structuredName?.suffixes, roundTripped.structuredName?.suffixes)
        assertEquals(original.emails.size, roundTripped.emails.size)
        assertEquals(original.emails[0].address, roundTripped.emails[0].address)
        assertEquals(original.phones.size, roundTripped.phones.size)
        assertEquals(original.phones[0].number, roundTripped.phones[0].number)
        assertEquals(original.notes, roundTripped.notes)
    }

    @Test fun serialize_handles_address_fields() {
        val contact = contact(
            fullName = "Dave",
            addresses = listOf(
                DecryptedAddress(
                    street = "123 Main St", locality = "Springfield",
                    region = "IL", postalCode = "62701", country = "US",
                    types = listOf("home"), isPrimary = true
                )
            )
        )
        val cards = serializer.serialize(contact)
        val vcard = Ezvcard.parse(cards[1].data).first()
        assertEquals(1, vcard.addresses.size)
        assertEquals("123 Main St", vcard.addresses[0].streetAddress)
        assertEquals("Springfield", vcard.addresses[0].locality)
    }

    @Test fun serialize_handles_organization() {
        val contact = contact(
            fullName = "Eve",
            organization = DecryptedOrganization(company = "Acme", department = "R&D", title = "Engineer")
        )
        val cards = serializer.serialize(contact)
        val vcard = Ezvcard.parse(cards[1].data).first()
        assertNotNull(vcard.organization)
        assertEquals("Acme", vcard.organization.values[0])
        assertEquals("R&D", vcard.organization.values[1])
        assertEquals("Engineer", vcard.titles[0].value)
    }

    @Test fun serialize_handles_photo() {
        val photoBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        val contact = contact(
            fullName = "Frank",
            photo = DecryptedPhoto(data = photoBytes, mimeType = "image/png")
        )
        val cards = serializer.serialize(contact)
        val vcard = Ezvcard.parse(cards[1].data).first()
        assertEquals(1, vcard.photos.size)
        assertTrue(vcard.photos[0].data.contentEquals(photoBytes))
    }

    @Test fun serialize_handles_im_accounts() {
        val contact = contact(
            fullName = "Grace",
            imAccounts = listOf(DecryptedIm(handle = "grace", protocol = "xmpp", types = emptyList()))
        )
        val cards = serializer.serialize(contact)
        val vcard = Ezvcard.parse(cards[1].data).first()
        assertEquals(1, vcard.impps.size)
        assertEquals("xmpp:grace", vcard.impps[0].uri.toString())
    }

    private fun contact(
        fullName: String? = null,
        protonUid: String? = null,
        structuredName: DecryptedStructuredName? = null,
        emails: List<DecryptedEmail> = emptyList(),
        phones: List<DecryptedPhone> = emptyList(),
        addresses: List<DecryptedAddress> = emptyList(),
        organization: DecryptedOrganization? = null,
        notes: List<String> = emptyList(),
        imAccounts: List<DecryptedIm> = emptyList(),
        photo: DecryptedPhoto? = null
    ) = DecryptedContact(
        protonContactId = "contact-1",
        protonUid = protonUid,
        fullName = fullName,
        structuredName = structuredName,
        emails = emails,
        phones = phones,
        addresses = addresses,
        organization = organization,
        notes = notes,
        imAccounts = imAccounts,
        photo = photo,
        verified = true,
        cardCount = 2,
        unverifiedCardCount = 0
    )
}
