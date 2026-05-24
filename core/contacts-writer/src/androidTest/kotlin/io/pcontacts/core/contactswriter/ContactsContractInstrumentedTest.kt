// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.contactswriter

import android.Manifest
import android.accounts.Account
import android.content.ContentProviderClient
import android.database.Cursor
import android.graphics.Bitmap
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.GroupMembership
import android.provider.ContactsContract.CommonDataKinds.Im
import android.provider.ContactsContract.CommonDataKinds.Note
import android.provider.ContactsContract.CommonDataKinds.Organization as CCOrganization
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.Photo
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.Groups
import android.provider.ContactsContract.RawContacts
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import java.io.ByteArrayOutputStream
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests that exercise ContactsContract round-trip
 * semantics on a real device or emulator. These validate what
 * Robolectric cannot: actual ContentValues written to the provider,
 * aggregation, tombstone behavior, photo BLOB round-trip, and group
 * membership.
 *
 * Each test method uses a unique account name to avoid cross-test
 * contamination. @After removes all RawContacts and Groups created
 * under the test account.
 */
@RunWith(AndroidJUnit4::class)
class ContactsContractInstrumentedTest {

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.WRITE_CONTACTS
    )

    private var provider: ContentProviderClient? = null
    private var account: Account? = null
    private val accountType = "io.pcontacts.test.instrumented"

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        provider = context.contentResolver
            .acquireContentProviderClient(ContactsContract.AUTHORITY)
            ?: throw AssertionError("Could not acquire ContentProviderClient for ContactsContract")
        account = Account("test-${UUID.randomUUID()}@proton.me", accountType)
    }

    @After
    fun tearDown() {
        val p = provider ?: return
        val a = account ?: return
        val uri = SyncAdapterUri.decorate(RawContacts.CONTENT_URI, a.name, a.type)
        p.delete(
            uri,
            "${RawContacts.ACCOUNT_TYPE} = ? AND ${RawContacts.ACCOUNT_NAME} = ?",
            arrayOf(a.type, a.name)
        )
        val groupUri = SyncAdapterUri.decorate(Groups.CONTENT_URI, a.name, a.type)
        p.delete(
            groupUri,
            "${Groups.ACCOUNT_TYPE} = ? AND ${Groups.ACCOUNT_NAME} = ?",
            arrayOf(a.type, a.name)
        )
        p.close()
    }

    private val testProvider: ContentProviderClient get() = provider!!
    private val testAccount: Account get() = account!!

    // ---- Create ----

    @Test
    fun create_contact_with_email_writes_structured_name_and_email() {
        val row = ContactRow(
            sourceId = "proton-c1",
            displayName = "Alice Doe",
            emails = listOf("alice@proton.me")
        )
        val applier = BatchApplier(testProvider)
        val result = applier.apply(testAccount, listOf(RawContactOpIntent.CreateContact(row)))
        assertEquals(1, result.insertedContacts)

        val rawId = findRawContactBySourceId("proton-c1")
        assertNotNull("RawContact must exist after create", rawId)

        val name = queryDataRow(rawId!!, StructuredName.CONTENT_ITEM_TYPE)
        assertNotNull("StructuredName Data row must exist", name)
        assertEquals("Alice Doe", name!!.getString(name.getColumnIndexOrThrow(StructuredName.DISPLAY_NAME)))
        name.close()

        val email = queryDataRow(rawId, Email.CONTENT_ITEM_TYPE)
        assertNotNull("Email Data row must exist", email)
        assertEquals("alice@proton.me", email!!.getString(email.getColumnIndexOrThrow(Email.ADDRESS)))
        assertEquals(1, email.getInt(email.getColumnIndexOrThrow(Email.IS_PRIMARY)))
        email.close()
    }

    @Test
    fun create_contact_with_structured_name_pieces() {
        val row = ContactRow(
            sourceId = "proton-c2",
            displayName = "Dr. Alice Marie Doe PhD",
            structuredName = StructuredName(
                given = "Alice",
                family = "Doe",
                middle = "Marie",
                prefix = "Dr",
                suffix = "PhD"
            ),
            emails = listOf("alice@proton.me")
        )
        val applier = BatchApplier(testProvider)
        applier.apply(testAccount, listOf(RawContactOpIntent.CreateContact(row)))

        val rawId = findRawContactBySourceId("proton-c2")!!
        val name = queryDataRow(rawId, StructuredName.CONTENT_ITEM_TYPE)!!
        assertEquals("Alice", name.getString(name.getColumnIndexOrThrow(StructuredName.GIVEN_NAME)))
        assertEquals("Doe", name.getString(name.getColumnIndexOrThrow(StructuredName.FAMILY_NAME)))
        assertEquals("Marie", name.getString(name.getColumnIndexOrThrow(StructuredName.MIDDLE_NAME)))
        assertEquals("Dr", name.getString(name.getColumnIndexOrThrow(StructuredName.PREFIX)))
        assertEquals("PhD", name.getString(name.getColumnIndexOrThrow(StructuredName.SUFFIX)))
        name.close()
    }

    @Test
    fun create_contact_with_multiple_emails_sets_primary_on_first() {
        val row = ContactRow(
            sourceId = "proton-c3",
            displayName = "Bob",
            emails = listOf("bob@proton.me", "bob.work@company.com", "bob.alt@gmail.com")
        )
        val applier = BatchApplier(testProvider)
        applier.apply(testAccount, listOf(RawContactOpIntent.CreateContact(row)))

        val rawId = findRawContactBySourceId("proton-c3")!!
        val emails = queryAllDataRows(rawId, Email.CONTENT_ITEM_TYPE)
        assertEquals(3, emails.size)

        val primary = emails.first { it["is_primary"] == "1" }
        assertEquals("bob@proton.me", primary[Email.ADDRESS])

        val nonPrimary = emails.filter { it["is_primary"] != "1" }
        assertEquals(2, nonPrimary.size)
        assertTrue(nonPrimary.any { it[Email.ADDRESS] == "bob.work@company.com" })
        assertTrue(nonPrimary.any { it[Email.ADDRESS] == "bob.alt@gmail.com" })
    }

    @Test
    fun create_contact_with_phones() {
        val row = ContactRow(
            sourceId = "proton-c4",
            displayName = "Carol",
            emails = listOf("carol@proton.me"),
            phones = listOf(
                PhoneEntry(number = "+1 555 0100", type = PhoneType.HOME),
                PhoneEntry(number = "+1 555 0101", type = PhoneType.MOBILE, isPrimary = true),
                PhoneEntry(number = "+1 555 0102", type = PhoneType.WORK)
            )
        )
        val applier = BatchApplier(testProvider)
        applier.apply(testAccount, listOf(RawContactOpIntent.CreateContact(row)))

        val rawId = findRawContactBySourceId("proton-c4")!!
        val phones = queryAllDataRows(rawId, Phone.CONTENT_ITEM_TYPE)
        assertEquals(3, phones.size)

        val mobile = phones.first { it["data1"] == "+1 555 0101" }
        assertEquals(Phone.TYPE_MOBILE.toString(), mobile["data2"])
        assertEquals("1", mobile["is_primary"])

        val home = phones.first { it["data1"] == "+1 555 0100" }
        assertEquals(Phone.TYPE_HOME.toString(), home["data2"])

        val work = phones.first { it["data1"] == "+1 555 0102" }
        assertEquals(Phone.TYPE_WORK.toString(), work["data2"])
    }

    @Test
    fun create_contact_with_postal_address() {
        val row = ContactRow(
            sourceId = "proton-c5",
            displayName = "Dave",
            emails = listOf("dave@proton.me"),
            addresses = listOf(
                PostalAddress(
                    street = "100 Main St",
                    city = "Springfield",
                    region = "IL",
                    postcode = "62704",
                    country = "USA",
                    type = PostalAddressType.HOME,
                    isPrimary = true
                )
            )
        )
        val applier = BatchApplier(testProvider)
        applier.apply(testAccount, listOf(RawContactOpIntent.CreateContact(row)))

        val rawId = findRawContactBySourceId("proton-c5")!!
        val addrs = queryAllDataRows(rawId, StructuredPostal.CONTENT_ITEM_TYPE)
        assertEquals(1, addrs.size)
        val addr = addrs[0]
        assertEquals("100 Main St", addr[StructuredPostal.STREET])
        assertEquals("Springfield", addr[StructuredPostal.CITY])
        assertEquals("IL", addr[StructuredPostal.REGION])
        assertEquals("62704", addr[StructuredPostal.POSTCODE])
        assertEquals("USA", addr[StructuredPostal.COUNTRY])
        assertEquals(StructuredPostal.TYPE_HOME.toString(), addr["data2"])
        assertEquals("1", addr["is_primary"])
    }

    @Test
    fun create_contact_with_organization() {
        val row = ContactRow(
            sourceId = "proton-c6",
            displayName = "Eve",
            emails = listOf("eve@proton.me"),
            organization = Organization(
                company = "Acme Inc.",
                department = "R&D",
                title = "Principal Engineer"
            )
        )
        val applier = BatchApplier(testProvider)
        applier.apply(testAccount, listOf(RawContactOpIntent.CreateContact(row)))

        val rawId = findRawContactBySourceId("proton-c6")!!
        val org = queryDataRow(rawId, CCOrganization.CONTENT_ITEM_TYPE)
        assertNotNull("Organization row must exist", org)
        assertEquals("Acme Inc.", org!!.getString(org.getColumnIndexOrThrow(CCOrganization.COMPANY)))
        assertEquals("R&D", org.getString(org.getColumnIndexOrThrow(CCOrganization.DEPARTMENT)))
        assertEquals("Principal Engineer", org.getString(org.getColumnIndexOrThrow(CCOrganization.TITLE)))
        org.close()
    }

    @Test
    fun create_contact_with_notes() {
        val row = ContactRow(
            sourceId = "proton-c7",
            displayName = "Frank",
            emails = listOf("frank@proton.me"),
            notes = listOf("First note", "Second note")
        )
        val applier = BatchApplier(testProvider)
        applier.apply(testAccount, listOf(RawContactOpIntent.CreateContact(row)))

        val rawId = findRawContactBySourceId("proton-c7")!!
        val notes = queryAllDataRows(rawId, Note.CONTENT_ITEM_TYPE)
        assertEquals(2, notes.size)
        val texts = notes.map { it[Note.NOTE] }.toSet()
        assertTrue(texts.contains("First note"))
        assertTrue(texts.contains("Second note"))
    }

    @Test
    fun create_contact_with_im_accounts() {
        val row = ContactRow(
            sourceId = "proton-c8",
            displayName = "Grace",
            emails = listOf("grace@proton.me"),
            imAccounts = listOf(
                ImAccount(handle = "grace@jabber.org", protocol = ImProtocol.JABBER),
                ImAccount(handle = "grace.skype", protocol = ImProtocol.SKYPE)
            )
        )
        val applier = BatchApplier(testProvider)
        applier.apply(testAccount, listOf(RawContactOpIntent.CreateContact(row)))

        val rawId = findRawContactBySourceId("proton-c8")!!
        val ims = queryAllDataRows(rawId, Im.CONTENT_ITEM_TYPE)
        assertEquals(2, ims.size)

        val jabber = ims.first { it[Im.DATA] == "grace@jabber.org" }
        assertEquals(Im.PROTOCOL_JABBER.toString(), jabber[Im.PROTOCOL])

        val skype = ims.first { it[Im.DATA] == "grace.skype" }
        assertEquals(Im.PROTOCOL_SKYPE.toString(), skype[Im.PROTOCOL])
    }

    @Test
    fun create_contact_with_inline_photo() {
        val photoBytes = createSmallJpeg()
        val row = ContactRow(
            sourceId = "proton-c9",
            displayName = "Heidi",
            emails = listOf("heidi@proton.me"),
            photo = ContactPhoto(photoBytes)
        )
        val applier = BatchApplier(testProvider)
        applier.apply(testAccount, listOf(RawContactOpIntent.CreateContact(row)))

        val rawId = findRawContactBySourceId("proton-c9")!!
        val photoCursor = queryDataRow(rawId, Photo.CONTENT_ITEM_TYPE)
        assertNotNull("Photo Data row must exist", photoCursor)
        val blob = photoCursor!!.getBlob(photoCursor.getColumnIndexOrThrow(Photo.PHOTO))
        assertNotNull("Photo BLOB must not be null", blob)
        assertTrue("Photo BLOB must have content", blob.isNotEmpty())
        photoCursor.close()
    }

    // ---- Update ----

    @Test
    fun update_preserves_raw_contact_id_and_replaces_data_rows() {
        val initial = ContactRow(
            sourceId = "proton-u1",
            displayName = "Alice Original",
            emails = listOf("alice.old@proton.me")
        )
        val applier = BatchApplier(testProvider)
        applier.apply(testAccount, listOf(RawContactOpIntent.CreateContact(initial)))

        val rawId = findRawContactBySourceId("proton-u1")!!

        val updated = ContactRow(
            sourceId = "proton-u1",
            displayName = "Alice Updated",
            emails = listOf("alice.new@proton.me", "alice.alt@proton.me"),
            phones = listOf(PhoneEntry(number = "+1 555 9999", type = PhoneType.MOBILE))
        )
        applier.apply(testAccount, listOf(RawContactOpIntent.UpdateContact(rawId, updated)))

        val rawIdAfter = findRawContactBySourceId("proton-u1")
        assertEquals("RawContacts._ID must be stable across updates", rawId, rawIdAfter)

        val name = queryDataRow(rawId, StructuredName.CONTENT_ITEM_TYPE)!!
        assertEquals("Alice Updated", name.getString(name.getColumnIndexOrThrow(StructuredName.DISPLAY_NAME)))
        name.close()

        val emails = queryAllDataRows(rawId, Email.CONTENT_ITEM_TYPE)
        assertEquals(2, emails.size)
        val addrs = emails.map { it[Email.ADDRESS] }.toSet()
        assertTrue(addrs.contains("alice.new@proton.me"))
        assertTrue(addrs.contains("alice.alt@proton.me"))
        assertTrue("Old email must be gone", !addrs.contains("alice.old@proton.me"))

        val phones = queryAllDataRows(rawId, Phone.CONTENT_ITEM_TYPE)
        assertEquals(1, phones.size)
        assertEquals("+1 555 9999", phones[0]["data1"])
    }

    // ---- Delete ----

    @Test
    fun delete_with_syncadapter_leaves_no_tombstone() {
        val row = ContactRow(
            sourceId = "proton-d1",
            displayName = "ToDelete",
            emails = listOf("del@proton.me")
        )
        val applier = BatchApplier(testProvider)
        applier.apply(testAccount, listOf(RawContactOpIntent.CreateContact(row)))
        assertNotNull("precondition: contact must exist", findRawContactBySourceId("proton-d1"))

        applier.apply(testAccount, listOf(RawContactOpIntent.DeleteContact("proton-d1")))

        val afterDelete = findRawContactBySourceId("proton-d1")
        assertNull("RawContact must be fully removed (no tombstone)", afterDelete)

        val tombstone = findRawContactBySourceId("proton-d1", includeTombstones = true)
        assertNull("No tombstone should exist when CALLER_IS_SYNCADAPTER=true", tombstone)
    }

    @Test
    fun deleteAllForAccount_removes_all_contacts() {
        val rows = (1..5).map {
            RawContactOpIntent.CreateContact(
                ContactRow(sourceId = "proton-da$it", displayName = "Name $it", emails = listOf("n$it@proton.me"))
            )
        }
        val applier = BatchApplier(testProvider)
        applier.apply(testAccount, rows)
        assertEquals(5, countRawContactsForAccount())

        val deleted = applier.deleteAllForAccount(testAccount)
        assertEquals(5, deleted)
        assertEquals(0, countRawContactsForAccount())
    }

    // ---- Groups ----

    @Test
    fun group_reconcile_creates_new_and_deletes_removed_groups() {
        val writer = LocalGroupsWriter(testProvider)

        val labels1 = listOf(
            ProtonLabel("label-a", "Family"),
            ProtonLabel("label-b", "Work")
        )
        val map1 = writer.reconcile(testAccount, labels1)
        assertEquals(2, map1.size)
        assertTrue(map1.containsKey("label-a"))
        assertTrue(map1.containsKey("label-b"))

        val labels2 = listOf(
            ProtonLabel("label-b", "Work"),
            ProtonLabel("label-c", "Friends")
        )
        val map2 = writer.reconcile(testAccount, labels2)
        assertEquals(2, map2.size)
        assertTrue("label-b must survive", map2.containsKey("label-b"))
        assertTrue("label-c must be added", map2.containsKey("label-c"))
        assertTrue("label-a must be removed", !map2.containsKey("label-a"))

        assertEquals("label-b row ID must be stable", map1["label-b"], map2["label-b"])
    }

    @Test
    fun group_membership_links_contact_to_group() {
        val writer = LocalGroupsWriter(testProvider)
        val labels = listOf(ProtonLabel("label-gm", "TestGroup"))
        val groupMap = writer.reconcile(testAccount, labels)
        val groupId = groupMap["label-gm"]!!

        val row = ContactRow(
            sourceId = "proton-gm1",
            displayName = "GroupMember",
            emails = listOf("gm@proton.me"),
            groupRowIds = listOf(groupId)
        )
        val applier = BatchApplier(testProvider)
        applier.apply(testAccount, listOf(RawContactOpIntent.CreateContact(row)))

        val rawId = findRawContactBySourceId("proton-gm1")!!
        val memberships = queryAllDataRows(rawId, GroupMembership.CONTENT_ITEM_TYPE)
        assertEquals(1, memberships.size)
        assertEquals(groupId.toString(), memberships[0][GroupMembership.GROUP_ROW_ID])
    }

    // ---- Idempotent sync ----

    @Test
    fun second_sync_with_same_data_produces_stable_raw_contact_ids() {
        val row = ContactRow(
            sourceId = "proton-idem",
            displayName = "Stable",
            emails = listOf("stable@proton.me"),
            phones = listOf(PhoneEntry(number = "+1 555 7777", type = PhoneType.MOBILE))
        )
        val applier = BatchApplier(testProvider)

        applier.apply(testAccount, listOf(RawContactOpIntent.CreateContact(row)))
        val rawIdFirst = findRawContactBySourceId("proton-idem")!!

        applier.apply(testAccount, listOf(RawContactOpIntent.UpdateContact(rawIdFirst, row)))
        val rawIdSecond = findRawContactBySourceId("proton-idem")!!

        assertEquals("RawContacts._ID must be stable across idempotent update", rawIdFirst, rawIdSecond)

        val emails = queryAllDataRows(rawIdSecond, Email.CONTENT_ITEM_TYPE)
        assertEquals("exactly one email after idempotent update", 1, emails.size)

        val phones = queryAllDataRows(rawIdSecond, Phone.CONTENT_ITEM_TYPE)
        assertEquals("exactly one phone after idempotent update", 1, phones.size)
    }

    // ---- RawContactReader ----

    @Test
    fun rawContactReader_returns_sourceId_to_rawContactId_map() {
        val rows = listOf(
            RawContactOpIntent.CreateContact(ContactRow("src-a", "A", emails = listOf("a@x"))),
            RawContactOpIntent.CreateContact(ContactRow("src-b", "B", emails = listOf("b@x"))),
            RawContactOpIntent.CreateContact(ContactRow("src-c", "C", emails = listOf("c@x")))
        )
        BatchApplier(testProvider).apply(testAccount, rows)

        val reader = RawContactReader(testProvider)
        val map = reader.readExisting(testAccount)
        assertEquals(3, map.size)
        assertTrue(map.containsKey("src-a"))
        assertTrue(map.containsKey("src-b"))
        assertTrue(map.containsKey("src-c"))
        assertTrue("IDs must be positive", map.values.all { it > 0 })
    }

    // ---- Full field set ----

    @Test
    fun create_contact_with_all_fields_and_query_back() {
        val photoBytes = createSmallJpeg()
        val writer = LocalGroupsWriter(testProvider)
        val groupMap = writer.reconcile(testAccount, listOf(ProtonLabel("label-full", "FullGroup")))

        val row = ContactRow(
            sourceId = "proton-full",
            displayName = "Alice Marie Doe",
            structuredName = StructuredName(
                given = "Alice",
                family = "Doe",
                middle = "Marie",
                prefix = "Dr",
                suffix = "PhD"
            ),
            emails = listOf("alice@proton.me", "alice.work@company.com"),
            phones = listOf(
                PhoneEntry("+1 555 0001", PhoneType.HOME),
                PhoneEntry("+1 555 0002", PhoneType.MOBILE, isPrimary = true)
            ),
            addresses = listOf(
                PostalAddress(
                    street = "1 Elm St",
                    city = "Metropolis",
                    region = "NY",
                    postcode = "10001",
                    country = "USA",
                    type = PostalAddressType.WORK
                )
            ),
            organization = Organization("Acme", "Engineering", "CTO"),
            notes = listOf("Important contact"),
            imAccounts = listOf(ImAccount("alice@jabber", ImProtocol.JABBER)),
            photo = ContactPhoto(photoBytes),
            groupRowIds = listOf(groupMap["label-full"]!!)
        )

        val applier = BatchApplier(testProvider)
        applier.apply(testAccount, listOf(RawContactOpIntent.CreateContact(row)))

        val rawId = findRawContactBySourceId("proton-full")!!

        assertEquals(2, queryAllDataRows(rawId, Email.CONTENT_ITEM_TYPE).size)
        assertEquals(2, queryAllDataRows(rawId, Phone.CONTENT_ITEM_TYPE).size)
        assertEquals(1, queryAllDataRows(rawId, StructuredPostal.CONTENT_ITEM_TYPE).size)
        assertEquals(1, queryAllDataRows(rawId, CCOrganization.CONTENT_ITEM_TYPE).size)
        assertEquals(1, queryAllDataRows(rawId, Note.CONTENT_ITEM_TYPE).size)
        assertEquals(1, queryAllDataRows(rawId, Im.CONTENT_ITEM_TYPE).size)
        assertEquals(1, queryAllDataRows(rawId, GroupMembership.CONTENT_ITEM_TYPE).size)

        val photoCursor = queryDataRow(rawId, Photo.CONTENT_ITEM_TYPE)
        assertNotNull("Photo must exist", photoCursor)
        photoCursor!!.close()
    }

    // ---- Batch chunking with real provider ----

    @Test
    fun batch_chunking_applies_all_contacts_across_chunks() {
        val intents = (1..20).map {
            RawContactOpIntent.CreateContact(
                ContactRow(sourceId = "proton-batch$it", displayName = "Batch $it", emails = listOf("b$it@x"))
            )
        }
        val applier = BatchApplier(testProvider)
        val result = applier.apply(testAccount, intents)
        assertEquals(20, result.insertedContacts)
        assertEquals(20, countRawContactsForAccount())
    }

    // ---- Helpers ----

    private fun findRawContactBySourceId(sourceId: String, includeTombstones: Boolean = false): Long? {
        val uri = if (includeTombstones) {
            RawContacts.CONTENT_URI.buildUpon()
                .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
                .build()
        } else {
            RawContacts.CONTENT_URI
        }
        val cursor = testProvider.query(
            uri,
            arrayOf(RawContacts._ID),
            "${RawContacts.SOURCE_ID} = ? AND ${RawContacts.ACCOUNT_TYPE} = ? AND ${RawContacts.ACCOUNT_NAME} = ?",
            arrayOf(sourceId, testAccount.type, testAccount.name),
            null
        ) ?: return null
        return cursor.use {
            if (it.moveToFirst()) it.getLong(0) else null
        }
    }

    private fun queryDataRow(rawContactId: Long, mimeType: String): Cursor? {
        val cursor = testProvider.query(
            Data.CONTENT_URI,
            null,
            "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} = ?",
            arrayOf(rawContactId.toString(), mimeType),
            null
        ) ?: return null
        return if (cursor.moveToFirst()) cursor else { cursor.close(); null }
    }

    private fun queryAllDataRows(rawContactId: Long, mimeType: String): List<Map<String, String?>> {
        val cursor = testProvider.query(
            Data.CONTENT_URI,
            null,
            "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} = ?",
            arrayOf(rawContactId.toString(), mimeType),
            null
        ) ?: return emptyList()
        return cursor.use { c ->
            val rows = mutableListOf<Map<String, String?>>()
            while (c.moveToNext()) {
                val row = mutableMapOf<String, String?>()
                for (i in 0 until c.columnCount) {
                    row[c.getColumnName(i)] = c.getString(i)
                }
                rows.add(row)
            }
            rows
        }
    }

    private fun countRawContactsForAccount(): Int {
        val cursor = testProvider.query(
            RawContacts.CONTENT_URI,
            arrayOf(RawContacts._ID),
            "${RawContacts.ACCOUNT_TYPE} = ? AND ${RawContacts.ACCOUNT_NAME} = ?",
            arrayOf(testAccount.type, testAccount.name),
            null
        ) ?: return 0
        return cursor.use { it.count }
    }

    private fun createSmallJpeg(): ByteArray {
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFFFF0000.toInt())
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        bitmap.recycle()
        return out.toByteArray()
    }
}
