// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.sync.contacts

import io.pcontacts.core.storage.db.entity.ContactMapEntity
import io.pcontacts.core.storage.db.entity.OutboxEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
