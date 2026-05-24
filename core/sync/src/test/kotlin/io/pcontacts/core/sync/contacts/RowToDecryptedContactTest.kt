// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.sync.contacts

import io.pcontacts.core.contactswriter.ContactPhoto
import io.pcontacts.core.contactswriter.ContactRow
import io.pcontacts.core.contactswriter.ImAccount
import io.pcontacts.core.contactswriter.ImProtocol
import io.pcontacts.core.contactswriter.Organization
import io.pcontacts.core.contactswriter.PhoneEntry
import io.pcontacts.core.contactswriter.PhoneType
import io.pcontacts.core.contactswriter.PostalAddress
import io.pcontacts.core.contactswriter.PostalAddressType
import io.pcontacts.core.contactswriter.StructuredName
import io.pcontacts.core.protoncontacts.DecryptedContact
import io.pcontacts.core.protoncontacts.DecryptedEmail
import io.pcontacts.core.protoncontacts.DecryptedPhone
import io.pcontacts.core.protoncontacts.DecryptedStructuredName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validates the inverse of [DecryptedContactToRow]. The round-trip is
 * approximate (multi-element structured-name lists collapse) but the
 * fields that survive must match.
 */
class RowToDecryptedContactTest {

    @Test fun converts_basic_email_only_row() {
        val row = ContactRow(
            sourceId = "ct-1",
            displayName = "Alice Smith",
            emails = listOf("alice@proton.me")
        )

        val dc = RowToDecryptedContact.convert(row, protonContactId = "ct-1", protonUid = "uid-abc")

        assertEquals("ct-1", dc.protonContactId)
        assertEquals("uid-abc", dc.protonUid)
        assertEquals("Alice Smith", dc.fullName)
        assertEquals(1, dc.emails.size)
        assertEquals("alice@proton.me", dc.emails[0].address)
        assertTrue(dc.emails[0].isPrimary)
    }

    @Test fun first_email_is_primary_rest_are_not() {
        val row = ContactRow(
            sourceId = "ct-1",
            displayName = "Alice",
            emails = listOf("alice@proton.me", "alice@work.com", "alice@alt.org")
        )

        val dc = RowToDecryptedContact.convert(row, protonContactId = "ct-1")

        assertTrue(dc.emails[0].isPrimary)
        assertTrue(dc.emails.drop(1).none { it.isPrimary })
    }

    @Test fun converts_structured_name_fields() {
        val row = ContactRow(
            sourceId = "ct-1",
            displayName = "Dr. Alice Marie Smith Jr.",
            structuredName = StructuredName(
                given = "Alice", family = "Smith",
                middle = "Marie", prefix = "Dr.", suffix = "Jr."
            ),
            emails = listOf("alice@proton.me")
        )

        val dc = RowToDecryptedContact.convert(row, protonContactId = "ct-1")

        assertNotNull(dc.structuredName)
        assertEquals("Alice", dc.structuredName!!.given)
        assertEquals("Smith", dc.structuredName!!.family)
        assertEquals(listOf("Marie"), dc.structuredName!!.additionalNames)
        assertEquals(listOf("Dr."), dc.structuredName!!.prefixes)
        assertEquals(listOf("Jr."), dc.structuredName!!.suffixes)
    }

    @Test fun all_null_structured_name_produces_null() {
        val row = ContactRow(
            sourceId = "ct-1",
            displayName = "Alice",
            structuredName = StructuredName(),
            emails = listOf("alice@proton.me")
        )

        val dc = RowToDecryptedContact.convert(row, protonContactId = "ct-1")

        assertNull(dc.structuredName)
    }

    @Test fun converts_phone_types_to_vcard_tokens() {
        val row = ContactRow(
            sourceId = "ct-1",
            displayName = "Alice",
            emails = listOf("alice@proton.me"),
            phones = listOf(
                PhoneEntry("555-1234", PhoneType.HOME, isPrimary = true),
                PhoneEntry("555-5678", PhoneType.MOBILE, isPrimary = false),
                PhoneEntry("555-0000", PhoneType.FAX_WORK, isPrimary = false),
                PhoneEntry("555-9999", PhoneType.OTHER, isPrimary = false)
            )
        )

        val dc = RowToDecryptedContact.convert(row, protonContactId = "ct-1")

        assertEquals(4, dc.phones.size)
        assertEquals(listOf("home"), dc.phones[0].types)
        assertTrue(dc.phones[0].isPrimary)
        assertEquals(listOf("cell"), dc.phones[1].types)
        assertEquals(listOf("fax", "work"), dc.phones[2].types)
        assertTrue(dc.phones[3].types.isEmpty())
    }

    @Test fun converts_addresses_with_types() {
        val row = ContactRow(
            sourceId = "ct-1",
            displayName = "Alice",
            emails = listOf("alice@proton.me"),
            addresses = listOf(
                PostalAddress(
                    street = "123 Main St", city = "Springfield",
                    region = "IL", postcode = "62701", country = "US",
                    type = PostalAddressType.HOME, isPrimary = true
                )
            )
        )

        val dc = RowToDecryptedContact.convert(row, protonContactId = "ct-1")

        assertEquals(1, dc.addresses.size)
        assertEquals("123 Main St", dc.addresses[0].street)
        assertEquals("Springfield", dc.addresses[0].locality)
        assertEquals(listOf("home"), dc.addresses[0].types)
        assertTrue(dc.addresses[0].isPrimary)
    }

    @Test fun converts_organization() {
        val row = ContactRow(
            sourceId = "ct-1",
            displayName = "Alice",
            emails = listOf("alice@proton.me"),
            organization = Organization(company = "Acme", department = "R&D", title = "Engineer")
        )

        val dc = RowToDecryptedContact.convert(row, protonContactId = "ct-1")

        assertNotNull(dc.organization)
        assertEquals("Acme", dc.organization!!.company)
        assertEquals("R&D", dc.organization!!.department)
        assertEquals("Engineer", dc.organization!!.title)
    }

    @Test fun converts_im_accounts_with_protocol_schemes() {
        val row = ContactRow(
            sourceId = "ct-1",
            displayName = "Alice",
            emails = listOf("alice@proton.me"),
            imAccounts = listOf(
                ImAccount(handle = "alice", protocol = ImProtocol.JABBER),
                ImAccount(handle = "alice.live", protocol = ImProtocol.SKYPE),
                ImAccount(handle = "alice", protocol = ImProtocol.CUSTOM, customProtocol = "matrix")
            )
        )

        val dc = RowToDecryptedContact.convert(row, protonContactId = "ct-1")

        assertEquals(3, dc.imAccounts.size)
        assertEquals("xmpp", dc.imAccounts[0].protocol)
        assertEquals("alice", dc.imAccounts[0].handle)
        assertEquals("skype", dc.imAccounts[1].protocol)
        assertEquals("matrix", dc.imAccounts[2].protocol)
    }

    @Test fun converts_photo() {
        val photoBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        val row = ContactRow(
            sourceId = "ct-1",
            displayName = "Alice",
            emails = listOf("alice@proton.me"),
            photo = ContactPhoto(data = photoBytes)
        )

        val dc = RowToDecryptedContact.convert(row, protonContactId = "ct-1")

        assertNotNull(dc.photo)
        assertTrue(dc.photo!!.data.contentEquals(photoBytes))
    }

    @Test fun converts_notes() {
        val row = ContactRow(
            sourceId = "ct-1",
            displayName = "Alice",
            emails = listOf("alice@proton.me"),
            notes = listOf("Note 1", "Note 2")
        )

        val dc = RowToDecryptedContact.convert(row, protonContactId = "ct-1")

        assertEquals(listOf("Note 1", "Note 2"), dc.notes)
    }

    @Test fun round_trip_through_forward_projection_preserves_core_fields() {
        val original = DecryptedContact(
            protonContactId = "ct-1",
            protonUid = "uid-rt",
            fullName = "Alice Smith",
            structuredName = DecryptedStructuredName(
                given = "Alice", family = "Smith",
                additionalNames = listOf("Marie"),
                prefixes = listOf("Dr."),
                suffixes = listOf("Jr.")
            ),
            emails = listOf(
                DecryptedEmail("alice@proton.me", isPrimary = true),
                DecryptedEmail("alice@work.com", isPrimary = false)
            ),
            phones = listOf(
                DecryptedPhone("555-1234", listOf("home"), isPrimary = true),
                DecryptedPhone("555-5678", listOf("cell"), isPrimary = false)
            ),
            verified = true,
            cardCount = 2,
            unverifiedCardCount = 0
        )

        val row = DecryptedContactToRow.convert(original)!!
        val roundTripped = RowToDecryptedContact.convert(row, "ct-1", "uid-rt")

        assertEquals(original.fullName, roundTripped.fullName)
        assertEquals(original.protonUid, roundTripped.protonUid)
        assertEquals(original.structuredName?.given, roundTripped.structuredName?.given)
        assertEquals(original.structuredName?.family, roundTripped.structuredName?.family)
        assertEquals(original.structuredName?.additionalNames, roundTripped.structuredName?.additionalNames)
        assertEquals(original.emails.size, roundTripped.emails.size)
        assertEquals(original.emails[0].address, roundTripped.emails[0].address)
        assertEquals(original.phones.size, roundTripped.phones.size)
        assertEquals(original.phones[0].number, roundTripped.phones[0].number)
    }
}
