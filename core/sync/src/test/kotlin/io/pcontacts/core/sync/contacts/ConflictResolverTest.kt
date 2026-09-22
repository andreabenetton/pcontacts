// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.sync.contacts

import io.pcontacts.core.storage.db.entity.ContactMapEntity
import io.pcontacts.core.storage.db.entity.OutboxEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConflictResolverTest {

    private val contactMap = WriteFakeContactMapDao()
    private val outbox = WriteFakeOutboxDao()

    private suspend fun conflicted() {
        contactMap.upsert(
            ContactMapEntity(
                protonContactId = "ct-1",
                protonUid = null,
                androidRawContactId = 100L,
                modifyTime = 500L,
                contentHash = "v2:abc",
                isVerified = true,
                deleted = false,
                syncStatus = ContactMapEntity.Status.CONFLICT,
                lastError = "conflict: fullName",
                lastSyncedAt = 1L
            )
        )
    }

    @Test fun use_local_clears_the_conflict_and_queues_a_force_update() = runTest {
        conflicted()

        resolveConflict(contactMap, outbox, "ct-1", useLocal = true, now = 10L)

        val mapping = contactMap.findByProtonId("ct-1")!!
        assertEquals(ContactMapEntity.Status.CLEAN, mapping.syncStatus)
        assertNull(mapping.lastError)
        val live = outbox.findLive("ct-1")!!
        assertEquals(OutboxEntity.OpType.FORCE_UPDATE, live.opType)
        assertEquals("", live.payloadHash)
    }

    @Test fun deleted_on_proton_and_use_local_re_keys_the_row_and_queues_a_create() = runTest {
        conflicted()
        contactMap.markConflict("ct-1", SERVER_DELETED_CONFLICT)
        outbox.enqueue("ct-1", OutboxEntity.OpType.UPDATE, "v2:local", 5L)
        outbox.findLive("ct-1")!!.let { outbox.quarantine(it.id, "HTTP 404") }

        resolveConflict(contactMap, outbox, "ct-1", useLocal = true, now = 10L)

        assertNull(contactMap.findByProtonId("ct-1"))
        val mapping = contactMap.findByProtonId("local-100")!!
        assertEquals(100L, mapping.androidRawContactId)
        assertEquals(ContactMapEntity.Status.CLEAN, mapping.syncStatus)
        assertNull(mapping.lastError)
        assertTrue(outbox.findByContact("ct-1").isEmpty())
        val queued = outbox.findLive("local-100")!!
        assertEquals(OutboxEntity.OpType.CREATE, queued.opType)
    }

    @Test fun deleted_on_proton_and_use_server_clears_every_queued_row_so_the_pull_can_delete() = runTest {
        conflicted()
        contactMap.markConflict("ct-1", SERVER_DELETED_CONFLICT)
        outbox.enqueue("ct-1", OutboxEntity.OpType.UPDATE, "v2:local", 5L)
        outbox.findLive("ct-1")!!.let { outbox.quarantine(it.id, "HTTP 404") }

        resolveConflict(contactMap, outbox, "ct-1", useLocal = false, now = 10L)

        val mapping = contactMap.findByProtonId("ct-1")!!
        assertEquals(ContactMapEntity.Status.CLEAN, mapping.syncStatus)
        assertNull(mapping.lastError)
        assertTrue(outbox.findByContact("ct-1").isEmpty())
    }

    @Test fun use_server_drops_the_live_row_and_forces_the_next_pull_to_rewrite() = runTest {
        conflicted()
        outbox.enqueue("ct-1", OutboxEntity.OpType.UPDATE, "v2:local", 5L)

        resolveConflict(contactMap, outbox, "ct-1", useLocal = false, now = 10L)

        val mapping = contactMap.findByProtonId("ct-1")!!
        assertEquals(ContactMapEntity.Status.CLEAN, mapping.syncStatus)
        assertEquals(0L, mapping.modifyTime)
        assertEquals("", mapping.contentHash)
        assertNull(outbox.findLive("ct-1"))
    }
}
