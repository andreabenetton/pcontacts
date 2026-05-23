// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.contactswriter

import android.accounts.Account
import android.provider.ContactsContract
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Structural tests for ContactsContractOps under Robolectric. The
 * actual ContentValues (e.g. DISPLAY_NAME = "Alice") aren't easily
 * introspectable via the public ContentProviderOperation API; full
 * value-shape verification ships in an instrumented test once an
 * emulator pipeline is set up. These tests cover URI shapes,
 * per-intent op counts, and operation kinds — which is what we'd
 * silently break by refactoring.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ContactsContractOpsTest {

    private val account = Account("alice@proton.me", "io.pcontacts.account")

    @Test fun create_emits_one_RawContacts_insert_plus_two_Data_inserts() {
        val ops = ContactsContractOps.build(
            account = account,
            intent = RawContactOpIntent.CreateContact(
                ContactRow(sourceId = "c1", displayName = "Alice", emails = listOf("alice@proton.me"))
            ),
            baseIdx = 0
        )
        assertEquals(3, ops.size)
        assertTrue("op 0 must be insert", ops[0].isInsert)
        assertTrue("op 1 must be insert", ops[1].isInsert)
        assertTrue("op 2 must be insert", ops[2].isInsert)

        // RawContacts URI carries caller_is_syncadapter and account params.
        val raw0Uri = ops[0].uri
        assertEquals("true", raw0Uri.getQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER))
        assertEquals("alice@proton.me", raw0Uri.getQueryParameter(RawContacts.ACCOUNT_NAME))

        // Child Data inserts target the bare Data URI — they back-reference,
        // so they shouldn't need the syncadapter param on the URI itself.
        assertEquals(Data.CONTENT_URI, ops[1].uri)
        assertEquals(Data.CONTENT_URI, ops[2].uri)
    }

    @Test fun update_emits_delete_data_then_two_data_reinserts() {
        val ops = ContactsContractOps.build(
            account = account,
            intent = RawContactOpIntent.UpdateContact(
                rawContactId = 100L,
                row = ContactRow(sourceId = "c1", displayName = "Alice", emails = listOf("alice@proton.me"))
            )
        )
        assertEquals(3, ops.size)
        assertTrue("op 0 must be delete", ops[0].isDelete)
        assertTrue("op 1 must be insert", ops[1].isInsert)
        assertTrue("op 2 must be insert", ops[2].isInsert)

        // Delete URI must carry CALLER_IS_SYNCADAPTER to avoid tombstone resurrection.
        assertEquals("true", ops[0].uri.getQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER))
    }

    @Test fun delete_emits_single_RawContacts_delete_with_selection_on_source_id() {
        val ops = ContactsContractOps.build(
            account = account,
            intent = RawContactOpIntent.DeleteContact(sourceId = "c1")
        )
        assertEquals(1, ops.size)
        assertTrue("op 0 must be delete", ops[0].isDelete)
        assertNotNull(ops[0].uri)
        assertEquals("true", ops[0].uri.getQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER))
    }

    @Test fun create_with_three_emails_emits_one_RawContacts_plus_StructuredName_plus_three_Email_rows() {
        val ops = ContactsContractOps.build(
            account = account,
            intent = RawContactOpIntent.CreateContact(
                ContactRow(
                    sourceId = "c1",
                    displayName = "Alice",
                    emails = listOf(
                        "alice@proton.me",     // position 0 → primary
                        "alice.work@x.com",
                        "alice.alt@x.com"
                    )
                )
            ),
            baseIdx = 0
        )
        // 1 RawContacts + 1 StructuredName + 3 Email = 5 ops.
        assertEquals(5, ops.size)
        assertTrue("op 0 must be RawContacts insert", ops[0].isInsert)
        // Op 0 RawContacts, op 1 StructuredName, ops 2..4 Email — all inserts.
        assertTrue(ops.all { it.isInsert })
    }

    @Test fun update_with_two_emails_emits_one_delete_plus_StructuredName_plus_two_Email_rows() {
        val ops = ContactsContractOps.build(
            account = account,
            intent = RawContactOpIntent.UpdateContact(
                rawContactId = 100L,
                row = ContactRow(
                    sourceId = "c1",
                    displayName = "Alice",
                    emails = listOf("primary@x.com", "alt@x.com")
                )
            )
        )
        assertEquals(4, ops.size)
        assertTrue(ops[0].isDelete)        // wipe child Data rows
        assertTrue(ops[1].isInsert)        // StructuredName
        assertTrue(ops[2].isInsert)        // Email[0]
        assertTrue(ops[3].isInsert)        // Email[1]
    }

    @Test fun contact_row_rejects_empty_email_list() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            ContactRow(sourceId = "c1", displayName = "Alice", emails = emptyList())
        }
    }

    @Test fun baseIdx_does_not_change_op_count_for_create() {
        // Smoke check — confirming the API accepts non-zero baseIdx without
        // throwing or adding/removing ops. Back-reference correctness lives
        // in BatchPlannerTest where the assembled batch matters.
        val ops = ContactsContractOps.build(
            account = account,
            intent = RawContactOpIntent.CreateContact(
                ContactRow(sourceId = "c1", displayName = "Alice", emails = listOf("alice@proton.me"))
            ),
            baseIdx = 449
        )
        assertEquals(3, ops.size)
    }
}
