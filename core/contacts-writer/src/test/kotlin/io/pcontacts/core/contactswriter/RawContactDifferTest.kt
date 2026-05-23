// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.contactswriter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests — no Android types, no Robolectric. The differ is the
 * load-bearing piece for idempotency, so the test surface is exhaustive.
 */
class RawContactDifferTest {

    @Test fun empty_target_and_empty_existing_yields_no_intents() {
        val intents = RawContactDiffer.diff(
            target = emptyList(),
            existing = emptyMap(),
            serverSourceIds = emptySet()
        )
        assertTrue(intents.isEmpty())
    }

    @Test fun all_new_target_rows_become_create_intents() {
        val intents = RawContactDiffer.diff(
            target = listOf(row("c1"), row("c2")),
            existing = emptyMap(),
            serverSourceIds = setOf("c1", "c2")
        )
        assertEquals(2, intents.size)
        assertTrue(intents.all { it is RawContactOpIntent.CreateContact })
        assertEquals(
            listOf("c1", "c2"),
            intents.map { (it as RawContactOpIntent.CreateContact).row.sourceId }
        )
    }

    @Test fun existing_rows_in_target_become_update_intents_with_correct_rawContactId() {
        val intents = RawContactDiffer.diff(
            target = listOf(row("c1"), row("c2")),
            existing = mapOf("c1" to 100L, "c2" to 200L),
            serverSourceIds = setOf("c1", "c2")
        )
        assertEquals(2, intents.size)
        val updates = intents.filterIsInstance<RawContactOpIntent.UpdateContact>()
        assertEquals(2, updates.size)
        assertEquals(100L, updates.first { it.row.sourceId == "c1" }.rawContactId)
        assertEquals(200L, updates.first { it.row.sourceId == "c2" }.rawContactId)
    }

    @Test fun server_removed_rows_become_delete_intents() {
        val intents = RawContactDiffer.diff(
            target = emptyList(),
            existing = mapOf("c1" to 100L, "c2" to 200L),
            serverSourceIds = emptySet()
        )
        assertEquals(2, intents.size)
        assertTrue(intents.all { it is RawContactOpIntent.DeleteContact })
        assertEquals(
            setOf("c1", "c2"),
            intents.map { (it as RawContactOpIntent.DeleteContact).sourceId }.toSet()
        )
    }

    @Test fun unchanged_rows_excluded_from_target_emit_no_intents() {
        // Caller's contract: hash matched, so target omits c2.
        // c2 IS still in serverSourceIds — it just doesn't need rewriting.
        val intents = RawContactDiffer.diff(
            target = listOf(row("c1")),
            existing = mapOf("c1" to 100L, "c2" to 200L),
            serverSourceIds = setOf("c1", "c2")
        )
        assertEquals(1, intents.size)
        assertTrue(intents.single() is RawContactOpIntent.UpdateContact)
        assertEquals(100L, (intents.single() as RawContactOpIntent.UpdateContact).rawContactId)
    }

    @Test fun mixed_create_update_delete_in_one_pass() {
        // c1: new → CREATE
        // c2: in existing AND in target (caller decided rewrite) → UPDATE
        // c3: in existing AND in serverSourceIds, NOT in target (unchanged) → no intent
        // c4: in existing, NOT in serverSourceIds → DELETE
        val intents = RawContactDiffer.diff(
            target = listOf(row("c1"), row("c2")),
            existing = mapOf("c2" to 200L, "c3" to 300L, "c4" to 400L),
            serverSourceIds = setOf("c1", "c2", "c3")
        )

        val creates = intents.filterIsInstance<RawContactOpIntent.CreateContact>()
        val updates = intents.filterIsInstance<RawContactOpIntent.UpdateContact>()
        val deletes = intents.filterIsInstance<RawContactOpIntent.DeleteContact>()
        assertEquals(setOf("c1"), creates.map { it.row.sourceId }.toSet())
        assertEquals(setOf("c2"), updates.map { it.row.sourceId }.toSet())
        assertEquals(setOf("c4"), deletes.map { it.sourceId }.toSet())
    }

    @Test fun idempotency_second_run_with_unchanged_state_yields_no_intents() {
        val rows = listOf(row("c1"), row("c2"), row("c3"))
        val first = RawContactDiffer.diff(
            target = rows,
            existing = emptyMap(),
            serverSourceIds = rows.map { it.sourceId }.toSet()
        )
        assertEquals(3, first.size)   // all CREATE

        // After applying, caller's mapping store has rows; caller's hash check
        // means target is empty on the second run.
        val second = RawContactDiffer.diff(
            target = emptyList(),
            existing = mapOf("c1" to 1L, "c2" to 2L, "c3" to 3L),
            serverSourceIds = rows.map { it.sourceId }.toSet()
        )
        assertTrue("expected empty intent list, got $second", second.isEmpty())
    }

    private fun row(sourceId: String) = ContactRow(
        sourceId = sourceId,
        displayName = "Name $sourceId",
        emails = listOf("$sourceId@proton.me")
    )
}
