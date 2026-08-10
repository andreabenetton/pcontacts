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

class QuarantinedChangeRefTest {

    @Test fun returns_empty_when_nothing_is_quarantined() = runTest {
        val outbox = WriteFakeOutboxDao()
        outbox.insert(sampleEntry("ct-1", OutboxEntity.OpType.UPDATE))

        assertTrue(buildQuarantinedChangeRefs(outbox, WriteFakeContactMapDao()).isEmpty())
    }

    @Test fun resolves_raw_contact_id_through_the_contact_map_for_an_update() = runTest {
        val outbox = WriteFakeOutboxDao()
        val contactMap = WriteFakeContactMapDao()
        contactMap.upsert(sampleMapping("ct-1", rawId = 100L))
        val id = outbox.insert(sampleEntry("ct-1", OutboxEntity.OpType.UPDATE))
        outbox.quarantine(id, "HttpException: 422")

        val refs = buildQuarantinedChangeRefs(outbox, contactMap)

        assertEquals(1, refs.size)
        assertEquals(id, refs[0].outboxId)
        assertEquals("ct-1", refs[0].protonContactId)
        assertEquals(100L, refs[0].androidRawContactId)
        assertEquals(ChangeOp.UPDATE, refs[0].op)
        assertEquals("HttpException: 422", refs[0].lastError)
        assertEquals(1_700_000_000L, refs[0].createdAt)
    }

    /**
     * A CREATE has no Proton id yet, so [ContactWriteEngine] stores
     * `local-<rawContactId>`. The raw id must come back out of the
     * placeholder, not out of the (nonexistent) contact_map row.
     */
    @Test fun decodes_raw_contact_id_from_the_local_placeholder_for_a_create() = runTest {
        val outbox = WriteFakeOutboxDao()
        val id = outbox.insert(sampleEntry("local-4321", OutboxEntity.OpType.CREATE))
        outbox.quarantine(id, "contact not found locally")

        val refs = buildQuarantinedChangeRefs(outbox, WriteFakeContactMapDao())

        assertEquals(4321L, refs.single().androidRawContactId)
        assertEquals(ChangeOp.CREATE, refs.single().op)
    }

    @Test fun yields_null_raw_contact_id_when_the_mapping_is_gone() = runTest {
        val outbox = WriteFakeOutboxDao()
        val id = outbox.insert(sampleEntry("ct-gone", OutboxEntity.OpType.DELETE))
        outbox.quarantine(id, "HttpException: 404")

        val refs = buildQuarantinedChangeRefs(outbox, WriteFakeContactMapDao())

        assertNull(refs.single().androidRawContactId)
        assertEquals(ChangeOp.DELETE, refs.single().op)
    }

    @Test fun yields_null_raw_contact_id_when_the_placeholder_is_malformed() = runTest {
        val outbox = WriteFakeOutboxDao()
        val id = outbox.insert(sampleEntry("local-not-a-number", OutboxEntity.OpType.CREATE))
        outbox.quarantine(id, "contact not found locally")

        assertNull(buildQuarantinedChangeRefs(outbox, WriteFakeContactMapDao()).single().androidRawContactId)
    }

    /**
     * `ContactWriteEngine` quarantines rows whose op_type it does not
     * recognise, so the view must render them rather than drop them.
     */
    @Test fun surfaces_unknown_op_types_with_a_null_op() = runTest {
        val outbox = WriteFakeOutboxDao()
        val id = outbox.insert(sampleEntry("ct-1", opType = 99))
        outbox.quarantine(id, "unknown op_type=99")

        val ref = buildQuarantinedChangeRefs(outbox, WriteFakeContactMapDao()).single()

        assertNull(ref.op)
        assertEquals("unknown op_type=99", ref.lastError)
    }

    @Test fun lists_oldest_first() = runTest {
        val outbox = WriteFakeOutboxDao()
        val newer = outbox.insert(sampleEntry("ct-2", OutboxEntity.OpType.UPDATE, createdAt = 1_700_000_500L))
        val older = outbox.insert(sampleEntry("ct-1", OutboxEntity.OpType.UPDATE, createdAt = 1_700_000_100L))
        outbox.quarantine(newer, "422")
        outbox.quarantine(older, "422")

        val refs = buildQuarantinedChangeRefs(outbox, WriteFakeContactMapDao())

        assertEquals(listOf("ct-1", "ct-2"), refs.map { it.protonContactId })
    }

    private fun sampleEntry(
        contactId: String,
        opType: Int,
        createdAt: Long = 1_700_000_000L
    ) = OutboxEntity(
        protonContactId = contactId,
        opType = opType,
        payloadHash = "hash-$contactId",
        createdAt = createdAt
    )

    private fun sampleMapping(id: String, rawId: Long) = ContactMapEntity(
        protonContactId = id,
        protonUid = null,
        androidRawContactId = rawId,
        modifyTime = 1_700_000_000L,
        contentHash = "hash-v1",
        isVerified = true,
        deleted = false,
        syncStatus = ContactMapEntity.Status.CLEAN,
        lastError = null,
        lastSyncedAt = 1_700_000_001L
    )
}
