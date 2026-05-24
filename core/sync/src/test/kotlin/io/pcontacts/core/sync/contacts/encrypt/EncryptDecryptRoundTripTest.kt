// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.sync.contacts.encrypt

import io.pcontacts.core.crypto.openpgp.BouncyCastleOpenPgpService
import io.pcontacts.core.protoncontacts.ContactDecrypter
import io.pcontacts.core.protoncontacts.ContactProcessor
import io.pcontacts.core.protoncontacts.ContactSerializer
import io.pcontacts.core.protoncontacts.DecryptedAddress
import io.pcontacts.core.protoncontacts.DecryptedContact
import io.pcontacts.core.protoncontacts.DecryptedEmail
import io.pcontacts.core.protoncontacts.DecryptedOrganization
import io.pcontacts.core.protoncontacts.DecryptedPhone
import io.pcontacts.core.protoncontacts.DecryptedStructuredName
import io.pcontacts.core.proton.api.contacts.ContactDto
import io.pcontacts.core.sync.contacts.decrypt.OpenPgpCardCryptoOp
import io.pcontacts.core.sync.contacts.decrypt.TestKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * End-to-end round-trip: DecryptedContact → ContactSerializer →
 * real PGP encrypt/sign → ContactDecrypter → VCardMerger →
 * DecryptedContact. Proves the full serialize→encrypt→decrypt→merge
 * chain preserves contact data.
 */
class EncryptDecryptRoundTripTest {

    private lateinit var serializer: ContactSerializer
    private lateinit var processor: ContactProcessor

    @Before fun setUp() {
        val openPgp = BouncyCastleOpenPgpService()
        val (_, unlocked) = TestKeys.armoredAndUnlocked("roundtrip".toCharArray())

        val encryptOp = OpenPgpCardEncryptOp.build(
            openPgp = openPgp,
            encryptionKeys = listOf(unlocked.public),
            signingKey = unlocked.private
        )
        serializer = ContactSerializer(encryptOp)

        val cryptoOp = OpenPgpCardCryptoOp.build(
            openPgp = openPgp,
            decryptionKeys = unlocked.allPrivateKeys,
            verificationKeys = listOf(unlocked.public)
        )
        processor = ContactProcessor(ContactDecrypter(cryptoOp))
    }

    private fun roundTrip(contact: DecryptedContact): DecryptedContact {
        val cards = serializer.serialize(contact)
        val dto = ContactDto(
            id = contact.protonContactId,
            name = contact.fullName ?: "",
            uid = contact.protonUid ?: "",
            size = 0,
            createTime = 0,
            modifyTime = 0,
            contactEmails = emptyList(),
            labelIds = emptyList(),
            cards = cards
        )
        return processor.process(dto)
    }

    @Test fun name_and_email_survive_round_trip() {
        val original = contact(
            fullName = "Alice Smith",
            structuredName = DecryptedStructuredName(given = "Alice", family = "Smith"),
            emails = listOf(DecryptedEmail("alice@proton.me", isPrimary = true))
        )
        val result = roundTrip(original)

        assertEquals("Alice Smith", result.fullName)
        assertEquals("Alice", result.structuredName?.given)
        assertEquals("Smith", result.structuredName?.family)
        assertEquals(1, result.emails.size)
        assertEquals("alice@proton.me", result.emails[0].address)
        assertTrue(result.verified)
    }

    @Test fun phones_survive_round_trip() {
        val original = contact(
            phones = listOf(
                DecryptedPhone("555-1234", listOf("home"), isPrimary = true),
                DecryptedPhone("555-5678", listOf("work"))
            )
        )
        val result = roundTrip(original)

        assertEquals(2, result.phones.size)
        val numbers = result.phones.map { it.number }.toSet()
        assertTrue(numbers.contains("555-1234"))
        assertTrue(numbers.contains("555-5678"))
    }

    @Test fun addresses_survive_round_trip() {
        val original = contact(
            addresses = listOf(
                DecryptedAddress(
                    street = "123 Main St",
                    locality = "Springfield",
                    region = "IL",
                    postalCode = "62701",
                    country = "US",
                    types = listOf("home")
                )
            )
        )
        val result = roundTrip(original)

        assertEquals(1, result.addresses.size)
        val addr = result.addresses[0]
        assertEquals("123 Main St", addr.street)
        assertEquals("Springfield", addr.locality)
        assertEquals("IL", addr.region)
        assertEquals("62701", addr.postalCode)
        assertEquals("US", addr.country)
    }

    @Test fun organization_survives_round_trip() {
        val original = contact(
            organization = DecryptedOrganization(
                company = "Acme Corp",
                department = "Engineering",
                title = "Staff Engineer"
            )
        )
        val result = roundTrip(original)

        assertEquals("Acme Corp", result.organization?.company)
        assertEquals("Engineering", result.organization?.department)
        assertEquals("Staff Engineer", result.organization?.title)
    }

    @Test fun notes_survive_round_trip() {
        val original = contact(notes = listOf("Met at conference 2026"))
        val result = roundTrip(original)

        assertEquals(1, result.notes.size)
        assertEquals("Met at conference 2026", result.notes[0])
    }

    @Test fun structured_name_with_prefix_and_suffix() {
        val original = contact(
            fullName = "Dr. Alice B. Smith Jr.",
            structuredName = DecryptedStructuredName(
                given = "Alice",
                family = "Smith",
                additionalNames = listOf("B."),
                prefixes = listOf("Dr."),
                suffixes = listOf("Jr.")
            )
        )
        val result = roundTrip(original)

        assertEquals("Alice", result.structuredName?.given)
        assertEquals("Smith", result.structuredName?.family)
        assertTrue(result.structuredName?.additionalNames?.contains("B.") == true)
        assertTrue(result.structuredName?.prefixes?.contains("Dr.") == true)
        assertTrue(result.structuredName?.suffixes?.contains("Jr.") == true)
    }

    @Test fun multiple_emails_with_types_survive_round_trip() {
        val original = contact(
            emails = listOf(
                DecryptedEmail("alice@proton.me", listOf("home"), isPrimary = true),
                DecryptedEmail("alice@work.com", listOf("work"))
            )
        )
        val result = roundTrip(original)

        assertEquals(2, result.emails.size)
        val addresses = result.emails.map { it.address }.toSet()
        assertTrue(addresses.contains("alice@proton.me"))
        assertTrue(addresses.contains("alice@work.com"))
    }

    @Test fun all_cards_are_verified() {
        val original = contact()
        val result = roundTrip(original)
        assertTrue(result.verified)
        assertEquals(0, result.unverifiedCardCount)
    }

    @Test fun uid_preserved_through_signed_card() {
        val original = contact(protonUid = "test-uid-42")
        val result = roundTrip(original)
        assertEquals("test-uid-42", result.protonUid)
    }

    private fun contact(
        protonContactId: String = "ct-roundtrip",
        protonUid: String? = "uid-roundtrip",
        fullName: String? = "Alice",
        structuredName: DecryptedStructuredName? = null,
        emails: List<DecryptedEmail> = listOf(DecryptedEmail("alice@proton.me", isPrimary = true)),
        phones: List<DecryptedPhone> = emptyList(),
        addresses: List<DecryptedAddress> = emptyList(),
        organization: DecryptedOrganization? = null,
        notes: List<String> = emptyList()
    ) = DecryptedContact(
        protonContactId = protonContactId,
        protonUid = protonUid,
        fullName = fullName,
        structuredName = structuredName,
        emails = emails,
        phones = phones,
        addresses = addresses,
        organization = organization,
        notes = notes,
        verified = true,
        cardCount = 2,
        unverifiedCardCount = 0
    )
}
