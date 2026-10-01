// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.sync.contacts

import io.pcontacts.core.storage.db.entity.ContactMapEntity
import io.pcontacts.core.storage.db.entity.OutboxEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingDeleteCancellerTest {

    private val contactMap = WriteFakeContactMapDao()
    private val outbox = WriteFakeOutboxDao()

    private suspend fun pendingDelete() {
        contactMap.upsert(
            ContactMapEntity(
                protonContactId = "ct-1",
                protonUid = null,
                androidRawContactId = 100L,
                modifyTime = 500L,
                contentHash = "v2:abc",
                isVerified = true,
                deleted = false,
                syncStatus = ContactMapEntity.Status.CLEAN,
                lastError = null,
                lastSyncedAt = 1L
            )
        )
        outbox.enqueue("ct-1", OutboxEntity.OpType.DELETE, "", 5L)
    }

    @Test fun with_the_tombstone_still_present_the_row_is_restored_and_the_mapping_untouched() = runTest {
        pendingDelete()

        val restored = cancelPendingDelete(contactMap, outbox, "ct-1") { 1 }

        assertTrue(restored)
        assertNull(outbox.findLive("ct-1"))
        assertEquals(500L, contactMap.findByProtonId("ct-1")!!.modifyTime)
    }

    @Test fun with_the_tombstone_already_purged_the_next_pull_recreates_the_contact() = runTest {
        pendingDelete()

        val restored = cancelPendingDelete(contactMap, outbox, "ct-1") { 0 }

        assertFalse(restored)
        assertNull(outbox.findLive("ct-1"))
        val mapping = contactMap.findByProtonId("ct-1")!!
        assertEquals(0L, mapping.modifyTime)
        assertEquals("", mapping.contentHash)
    }

    @Test fun a_queued_edit_is_not_dropped_by_mistake() = runTest {
        pendingDelete()
        outbox.enqueue("ct-1", OutboxEntity.OpType.UPDATE, "v2:new", 6L) // the edit already cancelled the delete

        cancelPendingDelete(contactMap, outbox, "ct-1") { 1 }

        assertEquals(OutboxEntity.OpType.UPDATE, outbox.findLive("ct-1")!!.opType)
    }

    @Test fun discarding_a_failed_deletion_keeps_the_contact() = runTest {
        pendingDelete()
        val failed = outbox.findLive("ct-1")!!
        outbox.quarantine(failed.id, "HTTP 422, Proton code 2001")
        val restoredFor = mutableListOf<String>()

        discardQuarantinedChange(contactMap, outbox, failed.id) {
            restoredFor += it
            1
        }

        assertTrue(outbox.findByContact("ct-1").isEmpty())
        assertEquals("the tombstone comes back, or it would queue the delete again", listOf("ct-1"), restoredFor)
    }

    @Test fun discarding_a_failed_edit_restores_nothing() = runTest {
        pendingDelete()
        val failed = outbox.findLive("ct-1")!!
        outbox.deleteById(failed.id)
        outbox.enqueue("ct-1", OutboxEntity.OpType.UPDATE, "h", 6L)
        val edit = outbox.findLive("ct-1")!!
        outbox.quarantine(edit.id, "HTTP 422, Proton code 2001")
        val restoredFor = mutableListOf<String>()

        discardQuarantinedChange(contactMap, outbox, edit.id) {
            restoredFor += it
            1
        }

        assertTrue(outbox.findByContact("ct-1").isEmpty())
        assertTrue(restoredFor.isEmpty())
    }
}
