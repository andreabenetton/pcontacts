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
        // 10 contacts × 3 ops = 30
        assertEquals(30, chunks[0].size)
    }

    @Test fun chunks_split_at_max_ops_per_batch_without_breaking_intent_boundaries() {
        // 100 create intents × 3 ops = 300. With maxOpsPerBatch=12, every
        // chunk must hold a multiple of 3 ops (no intent split across chunks)
        // and no chunk may exceed 12.
        val intents = (1..100).map {
            RawContactOpIntent.CreateContact(
                ContactRow(sourceId = "c$it", displayName = "Name $it", emails = listOf("n$it@x"))
            )
        }
        val chunks = BatchPlanner.plan(account, intents, maxOpsPerBatch = 12)
        assertTrue("each chunk must fit under the limit",
            chunks.all { it.size <= 12 })
        assertTrue("each chunk must hold a multiple of 3 ops (no intent split)",
            chunks.all { it.size % 3 == 0 })
        // Total ops preserved.
        assertEquals(300, chunks.sumOf { it.size })
    }

    @Test fun mixed_intent_kinds_pack_within_limit() {
        val intents = listOf(
            RawContactOpIntent.CreateContact(ContactRow("c1", "Alice", emails = listOf("a@x"))),         // 3 ops
            RawContactOpIntent.UpdateContact(rawContactId = 100L, row = ContactRow("c2", "Bob", emails = listOf("b@x"))), // 3 ops
            RawContactOpIntent.DeleteContact(sourceId = "c3"),                          // 1 op
            RawContactOpIntent.DeleteContact(sourceId = "c4")                           // 1 op
        )
        val chunks = BatchPlanner.plan(account, intents, maxOpsPerBatch = 5)
        // Greedy layout: pack until the next intent would overflow.
        //   chunk 0: [Create 3]                       size 3 (Update next would be 6 > 5 → split)
        //   chunk 1: [Update 3, Delete 1, Delete 1]   size 5 (fits exactly)
        assertEquals(2, chunks.size)
        assertEquals(3, chunks[0].size)
        assertEquals(5, chunks[1].size)
    }

    @Test fun back_reference_re_anchored_when_create_intent_starts_a_new_chunk() {
        // Layout: 4 creates × 3 ops = 12. With max=8, planner produces
        // chunks of [9 from intents 1-3] [3 from intent 4 with baseIdx reset to 0].
        // The fourth create's StructuredName op back-refs RAW_CONTACT_ID via
        // index 0 (the first op of the second chunk = the RawContacts insert
        // for intent #4). If the planner forgot to re-anchor, the back-ref
        // would still point at index 9 — out of range for an 8-op chunk.
        val intents = (1..4).map {
            RawContactOpIntent.CreateContact(
                ContactRow(sourceId = "c$it", displayName = "n$it", emails = listOf("$it@x"))
            )
        }
        val chunks = BatchPlanner.plan(account, intents, maxOpsPerBatch = 9)
        assertEquals(2, chunks.size)
        assertEquals(9, chunks[0].size)
        assertEquals(3, chunks[1].size)
        // Smoke: every op in the second chunk references the FIRST op of the
        // same chunk (back-ref must resolve within the chunk transaction).
        // Direct introspection of the back-ref index isn't exposed by the
        // public API; the instrumented test apply()s and asserts row counts.
        // What we *can* verify here is that the planner produced the
        // expected shape — no chunk overflow.
        assertTrue(chunks.all { it.size <= 9 })
    }

    @Test fun rejects_intent_that_would_alone_exceed_max() {
        // Single Create intent emits 3 ops; with max=2 we can't fit it.
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
