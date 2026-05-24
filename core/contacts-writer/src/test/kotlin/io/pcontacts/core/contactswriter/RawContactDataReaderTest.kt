// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.contactswriter

import android.database.MatrixCursor
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Im
import android.provider.ContactsContract.CommonDataKinds.Note
import android.provider.ContactsContract.CommonDataKinds.Organization as CCOrganization
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.Photo
import android.provider.ContactsContract.CommonDataKinds.StructuredName as CCStructuredName
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.Data
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class RawContactDataReaderTest {

    private val columns = arrayOf(
        Data.MIMETYPE,
        Data.DATA1, Data.DATA2, Data.DATA3, Data.DATA4,
        Data.DATA5, Data.DATA6, Data.DATA7, Data.DATA8,
        Data.DATA9, Data.DATA10, Data.DATA15,
        Data.IS_PRIMARY
    )

    private fun emptyRow(mime: String): Array<Any?> = arrayOf(
        mime, null, null, null, null, null, null, null, null, null, null, null, 0
    )

    private fun structuredNameRow(
        displayName: String?,
        given: String? = null,
        family: String? = null,
        prefix: String? = null,
        middle: String? = null,
        suffix: String? = null
    ): Array<Any?> = arrayOf(
        CCStructuredName.CONTENT_ITEM_TYPE,
        displayName, given, family, prefix, middle, suffix,
        null, null, null, null, null, 0
    )

    private fun emailRow(address: String, isPrimary: Boolean = false): Array<Any?> = arrayOf(
        Email.CONTENT_ITEM_TYPE,
        address, null, null, null, null, null, null, null, null, null, null,
        if (isPrimary) 1 else 0
    )

    private fun phoneRow(number: String, type: Int, isPrimary: Boolean = false): Array<Any?> = arrayOf(
        Phone.CONTENT_ITEM_TYPE,
        number, type, null, null, null, null, null, null, null, null, null,
        if (isPrimary) 1 else 0
    )

    private fun postalRow(
        type: Int,
        street: String? = null,
        poBox: String? = null,
        neighborhood: String? = null,
        city: String? = null,
        region: String? = null,
        postcode: String? = null,
        country: String? = null,
        isPrimary: Boolean = false
    ): Array<Any?> = arrayOf(
        StructuredPostal.CONTENT_ITEM_TYPE,
        null, type, null, street, poBox, neighborhood,
        city, region, postcode, country, null,
        if (isPrimary) 1 else 0
    )

    private fun orgRow(
        company: String? = null,
        title: String? = null,
        department: String? = null
    ): Array<Any?> = arrayOf(
        CCOrganization.CONTENT_ITEM_TYPE,
        company, null, null, title, department, null, null, null, null, null, null, 0
    )

    private fun noteRow(note: String): Array<Any?> = arrayOf(
        Note.CONTENT_ITEM_TYPE,
        note, null, null, null, null, null, null, null, null, null, null, 0
    )

    private fun imRow(
        handle: String,
        type: Int = Im.TYPE_OTHER,
        protocol: Int = Im.PROTOCOL_CUSTOM,
        customProtocol: String? = null
    ): Array<Any?> = arrayOf(
        Im.CONTENT_ITEM_TYPE,
        handle, type, null, null, protocol, customProtocol, null, null, null, null, null, 0
    )

    @Test fun parse_empty_cursor_returns_null() {
        val cursor = MatrixCursor(columns)
        assertNull(RawContactDataReader.parse(cursor, "ct-1"))
    }

    @Test fun parse_structured_name_and_email() {
        val cursor = MatrixCursor(columns).apply {
            addRow(structuredNameRow("Alice Smith", given = "Alice", family = "Smith"))
            addRow(emailRow("alice@proton.me", isPrimary = true))
        }
        val row = RawContactDataReader.parse(cursor, "ct-1")!!
        assertEquals("ct-1", row.sourceId)
        assertEquals("Alice Smith", row.displayName)
        assertNotNull(row.structuredName)
        assertEquals("Alice", row.structuredName!!.given)
        assertEquals("Smith", row.structuredName!!.family)
        assertEquals(1, row.emails.size)
        assertEquals("alice@proton.me", row.emails[0])
    }

    @Test fun parse_structured_name_with_all_pieces() {
        val cursor = MatrixCursor(columns).apply {
            addRow(structuredNameRow(
                "Dr. Alice Marie Smith Jr.",
                given = "Alice", family = "Smith",
                prefix = "Dr.", middle = "Marie", suffix = "Jr."
            ))
            addRow(emailRow("alice@proton.me"))
        }
        val row = RawContactDataReader.parse(cursor, "ct-1")!!
        assertEquals("Dr.", row.structuredName!!.prefix)
        assertEquals("Marie", row.structuredName!!.middle)
        assertEquals("Jr.", row.structuredName!!.suffix)
    }

    @Test fun parse_no_structured_name_pieces_produces_null_structured_name() {
        val cursor = MatrixCursor(columns).apply {
            addRow(structuredNameRow("Alice"))
            addRow(emailRow("alice@proton.me"))
        }
        val row = RawContactDataReader.parse(cursor, "ct-1")!!
        assertEquals("Alice", row.displayName)
        assertNull(row.structuredName)
    }

    @Test fun parse_multiple_emails_primary_first() {
        val cursor = MatrixCursor(columns).apply {
            addRow(structuredNameRow("Alice"))
            addRow(emailRow("alice@alt.org"))
            addRow(emailRow("alice@proton.me", isPrimary = true))
            addRow(emailRow("alice@work.com"))
        }
        val row = RawContactDataReader.parse(cursor, "ct-1")!!
        assertEquals(3, row.emails.size)
        assertEquals("alice@proton.me", row.emails[0])
    }

    @Test fun parse_phones_with_types() {
        val cursor = MatrixCursor(columns).apply {
            addRow(structuredNameRow("Alice"))
            addRow(emailRow("alice@proton.me"))
            addRow(phoneRow("555-1234", Phone.TYPE_HOME, isPrimary = true))
            addRow(phoneRow("555-5678", Phone.TYPE_MOBILE))
        }
        val row = RawContactDataReader.parse(cursor, "ct-1")!!
        assertEquals(2, row.phones.size)
        assertEquals("555-1234", row.phones[0].number)
        assertEquals(PhoneType.HOME, row.phones[0].type)
        assertTrue(row.phones[0].isPrimary)
        assertEquals("555-5678", row.phones[1].number)
        assertEquals(PhoneType.MOBILE, row.phones[1].type)
    }

    @Test fun parse_postal_address() {
        val cursor = MatrixCursor(columns).apply {
            addRow(structuredNameRow("Alice"))
            addRow(emailRow("alice@proton.me"))
            addRow(postalRow(
                type = StructuredPostal.TYPE_HOME,
                street = "123 Main St",
                city = "Springfield",
                region = "IL",
                postcode = "62701",
                country = "US",
                isPrimary = true
            ))
        }
        val row = RawContactDataReader.parse(cursor, "ct-1")!!
        assertEquals(1, row.addresses.size)
        assertEquals("123 Main St", row.addresses[0].street)
        assertEquals("Springfield", row.addresses[0].city)
        assertEquals(PostalAddressType.HOME, row.addresses[0].type)
        assertTrue(row.addresses[0].isPrimary)
    }

    @Test fun parse_organization() {
        val cursor = MatrixCursor(columns).apply {
            addRow(structuredNameRow("Alice"))
            addRow(emailRow("alice@proton.me"))
            addRow(orgRow(company = "Acme", title = "Engineer", department = "R&D"))
        }
        val row = RawContactDataReader.parse(cursor, "ct-1")!!
        assertNotNull(row.organization)
        assertEquals("Acme", row.organization!!.company)
        assertEquals("Engineer", row.organization!!.title)
        assertEquals("R&D", row.organization!!.department)
    }

    @Test fun parse_notes() {
        val cursor = MatrixCursor(columns).apply {
            addRow(structuredNameRow("Alice"))
            addRow(emailRow("alice@proton.me"))
            addRow(noteRow("Note 1"))
            addRow(noteRow("Note 2"))
        }
        val row = RawContactDataReader.parse(cursor, "ct-1")!!
        assertEquals(listOf("Note 1", "Note 2"), row.notes)
    }

    @Test fun parse_im_accounts() {
        val cursor = MatrixCursor(columns).apply {
            addRow(structuredNameRow("Alice"))
            addRow(emailRow("alice@proton.me"))
            addRow(imRow("alice", protocol = Im.PROTOCOL_JABBER))
            addRow(imRow("alice.live", protocol = Im.PROTOCOL_SKYPE, type = Im.TYPE_WORK))
            addRow(imRow("alice", protocol = Im.PROTOCOL_CUSTOM, customProtocol = "matrix"))
        }
        val row = RawContactDataReader.parse(cursor, "ct-1")!!
        assertEquals(3, row.imAccounts.size)
        assertEquals(ImProtocol.JABBER, row.imAccounts[0].protocol)
        assertEquals("alice", row.imAccounts[0].handle)
        assertEquals(ImProtocol.SKYPE, row.imAccounts[1].protocol)
        assertEquals(ImAccountType.WORK, row.imAccounts[1].type)
        assertEquals(ImProtocol.CUSTOM, row.imAccounts[2].protocol)
        assertEquals("matrix", row.imAccounts[2].customProtocol)
    }

    @Test fun parse_photo() {
        val photoBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        val cursor = MatrixCursor(columns).apply {
            addRow(structuredNameRow("Alice"))
            addRow(emailRow("alice@proton.me"))
            addRow(arrayOf(
                Photo.CONTENT_ITEM_TYPE,
                null, null, null, null, null, null, null, null, null, null,
                photoBytes, 0
            ))
        }
        val row = RawContactDataReader.parse(cursor, "ct-1")!!
        assertNotNull(row.photo)
        assertTrue(row.photo!!.data.contentEquals(photoBytes))
    }

    @Test fun parse_phone_only_contact() {
        val cursor = MatrixCursor(columns).apply {
            addRow(structuredNameRow("Alice"))
            addRow(phoneRow("555-0000", Phone.TYPE_MOBILE))
        }
        val row = RawContactDataReader.parse(cursor, "ct-1")!!
        assertTrue(row.emails.isEmpty())
        assertEquals(1, row.phones.size)
    }

    @Test fun parse_no_actionable_fields_returns_null() {
        val cursor = MatrixCursor(columns).apply {
            addRow(structuredNameRow("Alice"))
            addRow(noteRow("Just a note"))
        }
        assertNull(RawContactDataReader.parse(cursor, "ct-1"))
    }

    @Test fun parse_unknown_mime_type_ignored() {
        val cursor = MatrixCursor(columns).apply {
            addRow(structuredNameRow("Alice"))
            addRow(emailRow("alice@proton.me"))
            addRow(emptyRow("vnd.android.cursor.item/some_custom_type"))
        }
        val row = RawContactDataReader.parse(cursor, "ct-1")!!
        assertEquals(1, row.emails.size)
    }
}
