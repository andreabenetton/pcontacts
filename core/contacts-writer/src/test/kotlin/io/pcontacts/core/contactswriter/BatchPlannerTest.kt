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
        val chunks = BatchPlanner.plan(account, emptyList())
        assertTrue(chunks.isEmpty())
    }

    @Test fun small_intent_list_fits_in_one_chunk() {
        val intents = (1..10).map {
            RawContactOpIntent.CreateContact(
                ContactRow(sourceId = "c$it", displayName = "Name $it", emails = listOf("n$it@x"))
            )
        }
        val chunks = BatchPlanner.plan(account, intents)
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
        val chunks = BatchPlanner.plan(account, intents, maxOpsPerBatch = 12)
        assertTrue("each chunk must fit under the limit",
            chunks.all { it.size <= 12 })
        assertTrue("each chunk must hold a multiple of 4 ops (no intent split)",
            chunks.all { it.size % 4 == 0 })
        // Total ops preserved.
        assertEquals(400, chunks.sumOf { it.size })
    }

    @Test fun mixed_intent_kinds_pack_within_limit() {
        val intents = listOf(
            RawContactOpIntent.CreateContact(ContactRow("c1", "Alice", emails = listOf("a@x"))),         // 4 ops
            RawContactOpIntent.UpdateContact(rawContactId = 100L, row = ContactRow("c2", "Bob", emails = listOf("b@x"))), // 4 ops
            RawContactOpIntent.DeleteContact(sourceId = "c3"),                          // 1 op
            RawContactOpIntent.DeleteContact(sourceId = "c4")                           // 1 op
        )
        val chunks = BatchPlanner.plan(account, intents, maxOpsPerBatch = 5)
        // Greedy layout: pack until the next intent would overflow.
        //   chunk 0: [Create 4]                       size 4 (Update next would be 8 > 5 → split)
        //   chunk 1: [Update 4, Delete 1]             size 5 (fits exactly; second Delete would overflow)
        //   chunk 2: [Delete 1]                       size 1
        assertEquals(3, chunks.size)
        assertEquals(4, chunks[0].size)
        assertEquals(5, chunks[1].size)
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
        val chunks = BatchPlanner.plan(account, intents, maxOpsPerBatch = 9)
        assertEquals(2, chunks.size)
        assertEquals(8, chunks[0].size)
        assertEquals(8, chunks[1].size)
        assertTrue(chunks.all { it.size <= 9 })
    }

    @Test fun rejects_intent_that_would_alone_exceed_max() {
        // Single Create intent emits 4 ops; with max=2 we can't fit it.
        val intents = listOf(
            RawContactOpIntent.CreateContact(ContactRow("c1", "Alice", emails = listOf("a@x")))
        )
        assertThrows(IllegalArgumentException::class.java) {
            BatchPlanner.plan(account, intents, maxOpsPerBatch = 2)
        }
    }

    @Test fun rejects_non_positive_maxOpsPerBatch() {
        assertThrows(IllegalArgumentException::class.java) {
            BatchPlanner.plan(account, emptyList(), maxOpsPerBatch = 0)
        }
    }
}
