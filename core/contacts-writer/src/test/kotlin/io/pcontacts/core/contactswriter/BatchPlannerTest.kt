// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.contactswriter

import android.accounts.Account
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class BatchPlannerTest {

    private val account = Account("alice@proton.me", "io.pcontacts.account")

    @Test fun empty_intents_yields_no_chunks() {
        val chunks = BatchPlanner.plan(account, emptyList()).batches.map { it.ops }
        assertTrue(chunks.isEmpty())
    }

    @Test fun small_intent_list_fits_in_one_chunk() {
        val intents = (1..10).map {
            RawContactOpIntent.CreateContact(
                ContactRow(sourceId = "c$it", displayName = "Name $it", emails = listOf("n$it@x"))
            )
        }
        val chunks = BatchPlanner.plan(account, intents).batches.map { it.ops }
        assertEquals(1, chunks.size)
        // 10 contacts × 4 ops each (RawContacts + StructuredName + Email + chip) = 40.
        assertEquals(40, chunks[0].size)
    }

    @Test fun chunks_split_at_max_ops_per_batch_without_breaking_intent_boundaries() {
        // 100 create intents × 4 ops = 400. With maxOpsPerBatch=12, every
        // chunk must hold a multiple of 4 ops (no intent split across chunks)
        // and no chunk may exceed 12.
        val intents = (1..100).map {
            RawContactOpIntent.CreateContact(
                ContactRow(sourceId = "c$it", displayName = "Name $it", emails = listOf("n$it@x"))
            )
        }
        val chunks = BatchPlanner.plan(account, intents, maxOpsPerBatch = 12).batches.map { it.ops }
        assertTrue(
            "each chunk must fit under the limit",
            chunks.all { it.size <= 12 }
        )
        assertTrue(
            "each chunk must hold a multiple of 4 ops (no intent split)",
            chunks.all { it.size % 4 == 0 }
        )
        // Total ops preserved.
        assertEquals(400, chunks.sumOf { it.size })
    }

    @Test fun mixed_intent_kinds_pack_within_limit() {
        val intents = listOf(
            RawContactOpIntent.CreateContact(ContactRow("c1", "Alice", emails = listOf("a@x"))),         // 4 ops
            RawContactOpIntent.UpdateContact(rawContactId = 100L, row = ContactRow("c2", "Bob", emails = listOf("b@x"))), // 5 ops
            RawContactOpIntent.DeleteContact(sourceId = "c3"),                          // 1 op
            RawContactOpIntent.DeleteContact(sourceId = "c4")                           // 1 op
        )
        val chunks = BatchPlanner.plan(account, intents, maxOpsPerBatch = 6).batches.map { it.ops }
        // Greedy layout: pack until the next intent would overflow. An update starts with its
        // assert (review 2026-11-17, F5), so it is 5 ops and stays whole in its batch.
        //   chunk 0: [Create 4]                       size 4 (Update next would be 9 > 6 → split)
        //   chunk 1: [Update 5, Delete 1]             size 6 (fits exactly; second Delete would overflow)
        //   chunk 2: [Delete 1]                       size 1
        assertEquals(3, chunks.size)
        assertEquals(4, chunks[0].size)
        assertEquals(6, chunks[1].size)
        assertTrue("the assert travels with its update", chunks[1][0].isAssertQuery)
        assertEquals(1, chunks[2].size)
    }

    @Test fun back_reference_re_anchored_when_create_intent_starts_a_new_chunk() {
        // Layout: 4 creates × 4 ops = 16. With max=9, planner fits 2 creates
        // per chunk (8 ops); 4 creates → 2 chunks of 8.
        // The third create's StructuredName op back-refs RAW_CONTACT_ID via
        // index 0 (the first op of the second chunk = the RawContacts insert
        // for intent #3). If the planner forgot to re-anchor, the back-ref
        // would still point at index 8 — out of range for the chunk.
        val intents = (1..4).map {
            RawContactOpIntent.CreateContact(
                ContactRow(sourceId = "c$it", displayName = "n$it", emails = listOf("$it@x"))
            )
        }
        val chunks = BatchPlanner.plan(account, intents, maxOpsPerBatch = 9).batches.map { it.ops }
        assertEquals(2, chunks.size)
        assertEquals(8, chunks[0].size)
        assertEquals(8, chunks[1].size)
        assertTrue(chunks.all { it.size <= 9 })
    }

    @Test fun a_contact_with_more_ops_than_a_batch_holds_is_set_apart_and_the_others_are_planned() {
        // Review 2026-11-18: it used to fail the whole plan, so nothing around it was written.
        val manyPhones = (1..20).map { PhoneEntry(number = "+1555000$it", type = PhoneType.MOBILE) }
        val huge = create(ContactRow("huge", "Huge", emails = emptyList(), phones = manyPhones))
        val intents = listOf(
            RawContactOpIntent.CreateContact(ContactRow("a", "A", emails = listOf("a@x"))),
            huge,
            RawContactOpIntent.CreateContact(ContactRow("c", "C", emails = listOf("c@x")))
        )

        val plan = BatchPlanner.plan(account, intents, maxOpsPerBatch = 8)

        assertEquals(listOf(huge), plan.tooManyOps)
        assertEquals(listOf(listOf("a", "c")), plan.batches.map(::sourceIds))
        assertTrue(plan.batches.all { it.ops.first().isInsert })
    }

    @Test fun rejects_non_positive_maxOpsPerBatch() {
        assertThrows(IllegalArgumentException::class.java) {
            BatchPlanner.plan(account, emptyList(), maxOpsPerBatch = 0)
        }
    }

    @Test fun contacts_with_large_photos_are_split_by_bytes_not_only_by_op_count() {
        // Review 2026-11-17, F6: 20 contacts with a 90 KiB photo are far under 450 ops but ~1.8 MB,
        // past Binder's 1 MiB transaction buffer, and the same batch failed again on every run.
        val photo = ContactPhoto(ByteArray(90 * 1024) { it.toByte() })
        val intents = (1..20).map {
            RawContactOpIntent.CreateContact(
                ContactRow("c$it", "Contact $it", emails = emptyList(), photo = photo)
            )
        }

        val chunks = BatchPlanner.plan(account, intents).batches.map { it.ops }

        assertTrue("several batches, not one", chunks.size >= 5)
        val isRawInsert = { op: android.content.ContentProviderOperation ->
            op.isInsert && op.uri.path!!.endsWith("raw_contacts")
        }
        assertEquals(20, chunks.sumOf { chunk -> chunk.count(isRawInsert) })
        for (chunk in chunks) {
            assertTrue("every batch starts with its own RawContacts insert", isRawInsert(chunk.first()))
            val parcel = android.os.Parcel.obtain()
            chunk.forEach { it.writeToParcel(parcel, 0) }
            assertTrue("batch of ${parcel.dataSize()} bytes", parcel.dataSize() <= BatchPlanner.MAX_BYTES_PER_BATCH)
            parcel.recycle()
        }
    }

    @Test fun one_contact_over_the_byte_budget_travels_alone() {
        val big = ContactPhoto(ByteArray(64 * 1024))
        val intents = listOf(
            RawContactOpIntent.CreateContact(ContactRow("a", "A", emails = emptyList())),
            RawContactOpIntent.CreateContact(ContactRow("b", "B", emails = emptyList(), photo = big)),
            RawContactOpIntent.CreateContact(ContactRow("c", "C", emails = emptyList()))
        )

        val chunks = BatchPlanner.plan(account, intents, maxBytesPerBatch = 16 * 1024).batches.map { it.ops }

        assertEquals(3, chunks.size)
    }

    @Test fun only_a_contact_over_the_byte_budget_by_itself_is_marked_so() {
        val big = ContactPhoto(ByteArray(64 * 1024))
        val intents = listOf(
            RawContactOpIntent.CreateContact(ContactRow("a", "A", emails = emptyList())),
            RawContactOpIntent.CreateContact(ContactRow("b", "B", emails = emptyList(), photo = big)),
            RawContactOpIntent.CreateContact(ContactRow("c", "C", emails = emptyList())),
            RawContactOpIntent.CreateContact(ContactRow("d", "D", emails = emptyList()))
        )

        val plan = BatchPlanner.plan(account, intents, maxBytesPerBatch = 16 * 1024)

        assertEquals(listOf(listOf("a"), listOf("b"), listOf("c", "d")), plan.batches.map(::sourceIds))
        assertEquals(listOf(false, true, false), plan.batches.map { it.overBudget })
    }

    private fun create(row: ContactRow): RawContactOpIntent = RawContactOpIntent.CreateContact(row)

    private fun sourceIds(batch: BatchPlanner.Batch): List<String> =
        batch.intents.map { (it as RawContactOpIntent.CreateContact).row.sourceId }
}
