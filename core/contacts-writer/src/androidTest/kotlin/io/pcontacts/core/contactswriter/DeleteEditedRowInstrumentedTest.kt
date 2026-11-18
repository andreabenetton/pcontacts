// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.contactswriter

import android.Manifest
import android.accounts.Account
import android.content.ContentProviderClient
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A delete the sync makes never removes a row the user edited after the sync last looked at it
 * (review 2026-11-18, R2). The user's edits here go through the app-side resolver, a second
 * connection to the provider, not through the sync's own client.
 */
@RunWith(AndroidJUnit4::class)
class DeleteEditedRowInstrumentedTest {

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.WRITE_CONTACTS
    )

    private lateinit var syncClient: ContentProviderClient
    private lateinit var userResolver: ContentResolver
    private lateinit var account: Account

    @Before
    fun setUp() {
        userResolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        syncClient = userResolver.acquireContentProviderClient(ContactsContract.AUTHORITY)
            ?: throw AssertionError("Could not acquire ContentProviderClient for ContactsContract")
        account = Account("test-${UUID.randomUUID()}@proton.me", "io.pcontacts.test.instrumented")
    }

    @After
    fun tearDown() {
        syncClient.delete(
            SyncAdapterUri.decorate(RawContacts.CONTENT_URI, account.name, account.type),
            "${RawContacts.ACCOUNT_TYPE} = ? AND ${RawContacts.ACCOUNT_NAME} = ?",
            arrayOf(account.type, account.name)
        )
        syncClient.close()
    }

    @Test
    fun a_contact_deleted_on_proton_is_kept_when_it_was_edited_on_the_phone_meanwhile() {
        val rawId = create("proton-gone").single()
        userAddsPhone(rawId, "+1 555 0100")

        BatchApplier(syncClient).apply(account, listOf(RawContactOpIntent.DeleteContact("proton-gone")))

        assertEquals(listOf(ExistingRawContact(rawId, deleted = false)), rows("proton-gone"))
        assertEquals(listOf("+1 555 0100"), phones(rawId))
        assertEquals("the edit still waits to be sent", 1, flag(rawId, RawContacts.DIRTY))
    }

    @Test
    fun a_duplicate_row_is_kept_when_it_was_edited_on_the_phone_meanwhile() {
        val (kept, extra) = create("proton-dup", copies = 2)
        userAddsPhone(extra, "+1 555 0101")

        BatchApplier(syncClient).apply(account, listOf(RawContactOpIntent.DeleteRawContact(extra)))

        assertEquals(setOf(kept, extra), rows("proton-dup").map { it.rawContactId }.toSet())
        assertEquals(listOf("+1 555 0101"), phones(extra))
    }

    @Test
    fun a_contact_nobody_edited_is_deleted_by_source_id_and_by_row() {
        create("proton-clean")
        val (_, extra) = create("proton-clean-dup", copies = 2)

        BatchApplier(syncClient).apply(
            account,
            listOf(RawContactOpIntent.DeleteContact("proton-clean"), RawContactOpIntent.DeleteRawContact(extra))
        )

        assertTrue(rows("proton-clean").isEmpty())
        assertEquals(1, rows("proton-clean-dup").size)
    }

    @Test
    fun a_contact_the_user_deleted_is_still_removed_though_its_tombstone_is_dirty() {
        val bySourceId = create("proton-tomb").single()
        val byRow = create("proton-tomb-row").single()
        for (rawId in listOf(bySourceId, byRow)) {
            userResolver.delete(ContentUris.withAppendedId(RawContacts.CONTENT_URI, rawId), null, null)
            assertEquals("a user deletion leaves a tombstone", 1, flag(rawId, RawContacts.DELETED))
            assertEquals("and the tombstone is dirty", 1, flag(rawId, RawContacts.DIRTY))
        }

        BatchApplier(syncClient).apply(
            account,
            listOf(RawContactOpIntent.DeleteContact("proton-tomb"), RawContactOpIntent.DeleteRawContact(byRow))
        )

        assertTrue(rows("proton-tomb").isEmpty())
        assertTrue(rows("proton-tomb-row").isEmpty())
    }

    /** [copies] rows with [sourceId], written as the sync writes them; their ids, lowest first. */
    private fun create(sourceId: String, copies: Int = 1): List<Long> {
        val row = ContactRow(sourceId = sourceId, displayName = "Alice", emails = listOf("alice@proton.me"))
        repeat(copies) { BatchApplier(syncClient).apply(account, listOf(RawContactOpIntent.CreateContact(row))) }
        val ids = rows(sourceId).map { it.rawContactId }.sorted()
        assertEquals(copies, ids.size)
        ids.forEach { assertEquals("a row the sync wrote is clean", 0, flag(it, RawContacts.DIRTY)) }
        return ids
    }

    private fun userAddsPhone(rawContactId: Long, number: String) {
        val values = ContentValues().apply {
            put(Data.RAW_CONTACT_ID, rawContactId)
            put(Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE)
            put(Phone.NUMBER, number)
        }
        userResolver.insert(Data.CONTENT_URI, values)
        assertEquals("a user edit sets DIRTY", 1, flag(rawContactId, RawContacts.DIRTY))
    }

    private fun rows(sourceId: String): List<ExistingRawContact> =
        RawContactReader(syncClient).readExistingState(account).rowsBySourceId[sourceId].orEmpty()

    private fun phones(rawContactId: Long): List<String> = syncClient.query(
        Data.CONTENT_URI,
        arrayOf(Phone.NUMBER),
        "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} = ?",
        arrayOf(rawContactId.toString(), Phone.CONTENT_ITEM_TYPE),
        null
    )!!.use { cursor -> generateSequence { if (cursor.moveToNext()) cursor.getString(0) else null }.toList() }

    private fun flag(rawContactId: Long, column: String): Int = syncClient.query(
        SyncAdapterUri.decorate(RawContacts.CONTENT_URI, account.name, account.type),
        arrayOf(column),
        "${RawContacts._ID} = ?",
        arrayOf(rawContactId.toString()),
        null
    )!!.use { if (it.moveToFirst()) it.getInt(0) else -1 }
}
