// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.storage.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.pcontacts.core.storage.db.dao.ContactMapDao
import io.pcontacts.core.storage.db.dao.GroupMapDao
import io.pcontacts.core.storage.db.dao.OutboxDao
import io.pcontacts.core.storage.db.dao.OutboxEnqueue
import io.pcontacts.core.storage.db.dao.SyncStateDao
import io.pcontacts.core.storage.db.entity.ContactMapEntity
import io.pcontacts.core.storage.db.entity.GroupMapEntity
import io.pcontacts.core.storage.db.entity.OutboxEntity
import io.pcontacts.core.storage.db.entity.SyncStateEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Round-trip validation of the current schema against an in-memory
 * database. Runs under Robolectric so we don't need an emulator.
 * Migrations are covered separately by `MigrationTest`
 * (MigrationTestHelper over the JSON dumps in `:core:storage/schemas/`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class PcontactsDatabaseTest {

    private lateinit var db: PcontactsDatabase
    private lateinit var contactMapDao: ContactMapDao
    private lateinit var groupMapDao: GroupMapDao
    private lateinit var syncStateDao: SyncStateDao
    private lateinit var outboxDao: OutboxDao

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = DatabaseFactory.createInMemory(context)
        contactMapDao = db.contactMapDao()
        groupMapDao = db.groupMapDao()
        syncStateDao = db.syncStateDao()
        outboxDao = db.outboxDao()
    }

    @After fun tearDown() {
        db.close()
    }

    @Test fun contact_map_upsert_then_read_round_trips() = runTest {
        val row = sampleContact(id = "ct-1", rawId = 100L)
        contactMapDao.upsert(row)

        val read = contactMapDao.findByProtonId("ct-1")
        assertEquals(row, read)
    }

    @Test fun contact_map_upsert_replaces_on_same_primary_key() = runTest {
        contactMapDao.upsert(sampleContact(id = "ct-1", rawId = 100L, hash = "v1"))
        contactMapDao.upsert(sampleContact(id = "ct-1", rawId = 100L, hash = "v2"))

        val read = contactMapDao.findByProtonId("ct-1")
        assertNotNull(read)
        assertEquals("v2", read!!.contentHash)
    }

    @Test fun contact_map_find_by_raw_contact_id_uses_secondary_index() = runTest {
        contactMapDao.upsertAll(
            listOf(
                sampleContact(id = "ct-1", rawId = 100L),
                sampleContact(id = "ct-2", rawId = 200L),
                sampleContact(id = "ct-3", rawId = 300L)
            )
        )

        val read = contactMapDao.findByRawContactId(200L)
        assertNotNull(read)
        assertEquals("ct-2", read!!.protonContactId)
    }

    @Test fun contact_map_find_by_proton_uid_uses_secondary_index() = runTest {
        contactMapDao.upsert(sampleContact(id = "ct-1", rawId = 100L, uid = "vcard-uid-abc"))
        val read = contactMapDao.findByProtonUid("vcard-uid-abc")
        assertNotNull(read)
        assertEquals("ct-1", read!!.protonContactId)
    }

    @Test fun mark_deleted_excludes_from_live_listings() = runTest {
        contactMapDao.upsertAll(
            listOf(
                sampleContact(id = "live-1", rawId = 1L),
                sampleContact(id = "live-2", rawId = 2L),
                sampleContact(id = "tombstone", rawId = 3L)
            )
        )
        contactMapDao.markDeleted("tombstone")

        val liveIds = contactMapDao.listLiveProtonIds()
        assertEquals(setOf("live-1", "live-2"), liveIds.toSet())

        // Tombstones are still readable by primary key — needed so we know
        // not to reinsert them on the next sync pass.
        val tomb = contactMapDao.findByProtonId("tombstone")
        assertNotNull(tomb)
        assertTrue(tomb!!.deleted)
    }

    @Test fun delete_by_proton_ids_removes_only_the_named_rows() = runTest {
        contactMapDao.upsert(sampleContact(id = "ct-1", rawId = 100L))
        contactMapDao.upsert(sampleContact(id = "ct-2", rawId = 101L))
        contactMapDao.upsert(sampleContact(id = "ct-3", rawId = 102L))

        contactMapDao.deleteByProtonIds(listOf("ct-1", "ct-3"))

        assertEquals(listOf("ct-2"), contactMapDao.listLive().map { it.protonContactId })
    }

    @Test fun delete_by_proton_id_removes_the_row_entirely() = runTest {
        contactMapDao.upsert(sampleContact(id = "ct-1", rawId = 100L))
        contactMapDao.deleteByProtonId("ct-1")
        assertNull(contactMapDao.findByProtonId("ct-1"))
    }

    @Test fun group_map_round_trips() = runTest {
        val row = GroupMapEntity(
            protonLabelId = "label-1",
            androidGroupId = 50L,
            name = "Family",
            modifyTime = 1_700_000_000L
        )
        groupMapDao.upsert(row)

        val read = groupMapDao.findByLabelId("label-1")
        assertEquals(row, read)
    }

    @Test fun sync_state_round_trips_and_upserts() = runTest {
        val initial = SyncStateEntity(
            accountName = "uid-acct",
            lastFullSyncAt = 1_000L,
            lastIncrementalSyncAt = 2_000L,
            lastKnownTotal = 142
        )
        syncStateDao.upsert(initial)
        assertEquals(initial, syncStateDao.get("uid-acct"))

        val bumped = initial.copy(lastIncrementalSyncAt = 3_000L, lastKnownTotal = 143)
        syncStateDao.upsert(bumped)
        assertEquals(bumped, syncStateDao.get("uid-acct"))
    }

    @Test fun count_live_excludes_deleted_rows() = runTest {
        contactMapDao.upsertAll(
            listOf(
                sampleContact(id = "a", rawId = 1L),
                sampleContact(id = "b", rawId = 2L),
                sampleContact(id = "c", rawId = 3L)
            )
        )
        contactMapDao.markDeleted("b")
        assertEquals(2, contactMapDao.countLive())
    }

    @Test fun count_unverified_counts_only_live_unverified_rows() = runTest {
        contactMapDao.upsertAll(
            listOf(
                sampleContact(id = "verified-1", rawId = 1L, verified = true),
                sampleContact(id = "verified-2", rawId = 2L, verified = true),
                sampleContact(id = "unverified-1", rawId = 3L, verified = false),
                sampleContact(id = "unverified-2", rawId = 4L, verified = false),
                sampleContact(id = "deleted-unverified", rawId = 5L, verified = false)
            )
        )
        contactMapDao.markDeleted("deleted-unverified")
        assertEquals(2, contactMapDao.countUnverified())
    }

    @Test fun count_unverified_returns_zero_when_all_verified() = runTest {
        contactMapDao.upsertAll(
            listOf(
                sampleContact(id = "a", rawId = 1L, verified = true),
                sampleContact(id = "b", rawId = 2L, verified = true)
            )
        )
        assertEquals(0, contactMapDao.countUnverified())
    }

    @Test fun list_unverified_returns_only_live_unverified_rows() = runTest {
        contactMapDao.upsertAll(
            listOf(
                sampleContact(id = "verified-1", rawId = 1L, verified = true),
                sampleContact(id = "unverified-1", rawId = 2L, verified = false),
                sampleContact(id = "unverified-2", rawId = 3L, verified = false),
                sampleContact(id = "deleted-unverified", rawId = 4L, verified = false)
            )
        )
        contactMapDao.markDeleted("deleted-unverified")
        val rows = contactMapDao.listUnverified()
        assertEquals(setOf("unverified-1", "unverified-2"), rows.map { it.protonContactId }.toSet())
        // Returned rows must surface the rawId so the settings UI can
        // resolve display names + build the open-in-Contacts intent.
        assertEquals(setOf(2L, 3L), rows.map { it.androidRawContactId }.toSet())
    }

    @Test fun max_last_synced_at_returns_latest_timestamp() = runTest {
        contactMapDao.upsertAll(
            listOf(
                sampleContact(id = "a", rawId = 1L).copy(lastSyncedAt = 1_000L),
                sampleContact(id = "b", rawId = 2L).copy(lastSyncedAt = 3_000L),
                sampleContact(id = "c", rawId = 3L).copy(lastSyncedAt = 2_000L)
            )
        )
        assertEquals(3_000L, contactMapDao.maxLastSyncedAt())
    }

    @Test fun max_last_synced_at_excludes_deleted_rows() = runTest {
        contactMapDao.upsertAll(
            listOf(
                sampleContact(id = "a", rawId = 1L).copy(lastSyncedAt = 1_000L),
                sampleContact(id = "b", rawId = 2L).copy(lastSyncedAt = 5_000L)
            )
        )
        contactMapDao.markDeleted("b")
        assertEquals(1_000L, contactMapDao.maxLastSyncedAt())
    }

    @Test fun max_last_synced_at_returns_null_when_table_empty() = runTest {
        assertNull(contactMapDao.maxLastSyncedAt())
    }

    // --- contact_map: lastKnownServerPayloadHash column ---

    @Test fun contact_map_last_known_server_payload_hash_defaults_to_null() = runTest {
        contactMapDao.upsert(sampleContact(id = "ct-1", rawId = 100L))
        val read = contactMapDao.findByProtonId("ct-1")
        assertNull(read!!.lastKnownServerPayloadHash)
    }

    @Test fun contact_map_last_known_server_payload_hash_round_trips() = runTest {
        val row = sampleContact(id = "ct-1", rawId = 100L).copy(
            lastKnownServerPayloadHash = "abc123"
        )
        contactMapDao.upsert(row)
        val read = contactMapDao.findByProtonId("ct-1")
        assertEquals("abc123", read!!.lastKnownServerPayloadHash)
    }

    // --- contact_map: merge base and push-path updates (v3) ---

    @Test fun contact_map_merge_base_defaults_to_null_and_round_trips_bytes() = runTest {
        contactMapDao.upsert(sampleContact(id = "ct-1", rawId = 100L))
        assertNull(contactMapDao.mergeBase("ct-1"))

        contactMapDao.setMergeBase("ct-1", byteArrayOf(1, 2, 3))

        assertTrue(byteArrayOf(1, 2, 3).contentEquals(contactMapDao.mergeBase("ct-1")))
        assertTrue(byteArrayOf(1, 2, 3).contentEquals(contactMapDao.findByProtonId("ct-1")!!.lastKnownServerPayload))
    }

    @Test fun contact_map_set_merge_base_on_unknown_contact_is_a_noop() = runTest {
        contactMapDao.setMergeBase("ct-missing", byteArrayOf(1))
        assertNull(contactMapDao.findByProtonId("ct-missing"))
    }

    @Test fun contact_map_mark_clean_resets_status_error_and_stamps_time() = runTest {
        contactMapDao.upsert(
            sampleContact(id = "ct-1", rawId = 100L)
                .copy(syncStatus = ContactMapEntity.Status.CONFLICT, lastError = "conflict: fullName")
        )

        contactMapDao.markClean("ct-1", now = 1_800_000_000L)

        val read = contactMapDao.findByProtonId("ct-1")!!
        assertEquals(ContactMapEntity.Status.CLEAN, read.syncStatus)
        assertNull(read.lastError)
        assertEquals(1_800_000_000L, read.lastSyncedAt)
    }

    @Test fun contact_map_mark_conflict_sets_status_and_reason_and_keeps_merge_base() = runTest {
        contactMapDao.upsert(sampleContact(id = "ct-1", rawId = 100L))
        contactMapDao.setMergeBase("ct-1", byteArrayOf(9))

        contactMapDao.markConflict("ct-1", "conflict: no merge base")

        val read = contactMapDao.findByProtonId("ct-1")!!
        assertEquals(ContactMapEntity.Status.CONFLICT, read.syncStatus)
        assertEquals("conflict: no merge base", read.lastError)
        assertTrue(byteArrayOf(9).contentEquals(read.lastKnownServerPayload))
    }

    @Test fun contact_map_force_refetch_zeroes_modify_time_and_hash() = runTest {
        contactMapDao.upsert(sampleContact(id = "ct-1", rawId = 100L, hash = "v1"))

        contactMapDao.forceRefetch("ct-1")

        val read = contactMapDao.findByProtonId("ct-1")!!
        assertEquals(0L, read.modifyTime)
        assertEquals("", read.contentHash)
    }

    // --- outbox: one live row per contact (v3) ---

    @Test fun outbox_enqueue_inserts_when_no_live_row() = runTest {
        val result = outboxDao.enqueue("ct-1", OutboxEntity.OpType.UPDATE, "h1", now = 10L)

        assertEquals(OutboxEnqueue.INSERTED, result)
        assertEquals("h1", outboxDao.findLive("ct-1")!!.payloadHash)
    }

    @Test fun outbox_enqueue_replaces_hash_of_live_update_and_resets_backoff() = runTest {
        outboxDao.enqueue("ct-1", OutboxEntity.OpType.UPDATE, "h1", now = 10L)
        val id = outboxDao.findLive("ct-1")!!.id
        outboxDao.recordFailure(id, attempts = 2, error = "HTTP 503", nextAt = 999L)

        val result = outboxDao.enqueue("ct-1", OutboxEntity.OpType.UPDATE, "h2", now = 20L)

        assertEquals(OutboxEnqueue.REPLACED, result)
        val live = outboxDao.findLive("ct-1")!!
        assertEquals(id, live.id)
        assertEquals("h2", live.payloadHash)
        assertEquals(0, live.attempts)
        assertNull(live.lastError)
        assertEquals(0L, live.nextAttemptAt)
        assertEquals(1, outboxDao.findByContact("ct-1").size)
    }

    @Test fun outbox_enqueue_keeps_create_when_an_update_follows() = runTest {
        outboxDao.enqueue("local-42", OutboxEntity.OpType.CREATE, "h1", now = 10L)

        assertEquals(OutboxEnqueue.REPLACED, outboxDao.enqueue("local-42", OutboxEntity.OpType.UPDATE, "h2", now = 20L))

        val live = outboxDao.findLive("local-42")!!
        assertEquals(OutboxEntity.OpType.CREATE, live.opType)
        assertEquals("h2", live.payloadHash)
    }

    @Test fun outbox_enqueue_drops_an_unpushed_create_on_delete() = runTest {
        outboxDao.enqueue("local-42", OutboxEntity.OpType.CREATE, "h1", now = 10L)

        assertEquals(OutboxEnqueue.DROPPED, outboxDao.enqueue("local-42", OutboxEntity.OpType.DELETE, "", now = 20L))

        assertNull(outboxDao.findLive("local-42"))
        assertTrue(outboxDao.findByContact("local-42").isEmpty())
    }

    @Test fun outbox_enqueue_delete_supersedes_update_and_restarts_grace() = runTest {
        outboxDao.enqueue("ct-1", OutboxEntity.OpType.UPDATE, "h1", now = 10L)

        assertEquals(OutboxEnqueue.REPLACED, outboxDao.enqueue("ct-1", OutboxEntity.OpType.DELETE, "", now = 500L))

        val live = outboxDao.findLive("ct-1")!!
        assertEquals(OutboxEntity.OpType.DELETE, live.opType)
        assertEquals("", live.payloadHash)
        assertEquals(500L, live.createdAt)
    }

    @Test fun outbox_enqueue_update_cancels_a_pending_delete() = runTest {
        outboxDao.enqueue("ct-1", OutboxEntity.OpType.DELETE, "", now = 10L)

        assertEquals(OutboxEnqueue.REPLACED, outboxDao.enqueue("ct-1", OutboxEntity.OpType.UPDATE, "h1", now = 20L))

        val live = outboxDao.findLive("ct-1")!!
        assertEquals(OutboxEntity.OpType.UPDATE, live.opType)
        assertEquals("h1", live.payloadHash)
        assertTrue(outboxDao.listPendingDeletes().isEmpty())
    }

    @Test fun outbox_enqueue_force_update_wins_over_update_either_way() = runTest {
        outboxDao.enqueue("ct-1", OutboxEntity.OpType.UPDATE, "h1", now = 10L)
        outboxDao.enqueue("ct-1", OutboxEntity.OpType.FORCE_UPDATE, "", now = 20L)
        assertEquals(OutboxEntity.OpType.FORCE_UPDATE, outboxDao.findLive("ct-1")!!.opType)

        assertEquals(OutboxEnqueue.UNCHANGED, outboxDao.enqueue("ct-1", OutboxEntity.OpType.UPDATE, "h2", now = 30L))

        assertEquals(OutboxEntity.OpType.FORCE_UPDATE, outboxDao.findLive("ct-1")!!.opType)
    }

    @Test fun outbox_enqueue_same_op_same_hash_is_unchanged_and_keeps_backoff() = runTest {
        outboxDao.enqueue("ct-1", OutboxEntity.OpType.UPDATE, "h1", now = 10L)
        val id = outboxDao.findLive("ct-1")!!.id
        outboxDao.recordFailure(id, attempts = 1, error = "HTTP 503", nextAt = 999L)

        assertEquals(OutboxEnqueue.UNCHANGED, outboxDao.enqueue("ct-1", OutboxEntity.OpType.UPDATE, "h1", now = 20L))

        val live = outboxDao.findLive("ct-1")!!
        assertEquals(1, live.attempts)
        assertEquals(999L, live.nextAttemptAt)
    }

    @Test fun outbox_enqueue_ignores_quarantined_rows() = runTest {
        outboxDao.enqueue("ct-1", OutboxEntity.OpType.UPDATE, "h1", now = 10L)
        outboxDao.quarantine(outboxDao.findLive("ct-1")!!.id, "HTTP 422")

        assertEquals(OutboxEnqueue.INSERTED, outboxDao.enqueue("ct-1", OutboxEntity.OpType.UPDATE, "h2", now = 20L))

        assertEquals(2, outboxDao.findByContact("ct-1").size)
        assertEquals("h2", outboxDao.findLive("ct-1")!!.payloadHash)
    }

    @Test fun outbox_delete_if_unchanged_keeps_a_row_replaced_meanwhile() = runTest {
        outboxDao.enqueue("ct-1", OutboxEntity.OpType.UPDATE, "h1", now = 10L)
        val id = outboxDao.findLive("ct-1")!!.id
        outboxDao.enqueue("ct-1", OutboxEntity.OpType.UPDATE, "h2", now = 20L)

        outboxDao.deleteIfUnchanged(id, OutboxEntity.OpType.UPDATE, "h1")
        assertEquals("h2", outboxDao.findLive("ct-1")!!.payloadHash)

        outboxDao.deleteIfUnchanged(id, OutboxEntity.OpType.UPDATE, "h2")
        assertNull(outboxDao.findLive("ct-1"))
    }

    // --- outbox ---

    @Test fun outbox_insert_and_list_ready_round_trips() = runTest {
        val entry = sampleOutbox(contactId = "ct-1", opType = OutboxEntity.OpType.UPDATE)
        outboxDao.insert(entry)

        val ready = outboxDao.listReady(now = 2_000_000_000L)
        assertEquals(1, ready.size)
        assertEquals("ct-1", ready[0].protonContactId)
        assertEquals(OutboxEntity.OpType.UPDATE, ready[0].opType)
    }

    @Test fun outbox_list_ready_excludes_quarantined_entries() = runTest {
        outboxDao.insert(sampleOutbox(contactId = "ct-1", opType = OutboxEntity.OpType.UPDATE))
        outboxDao.insert(sampleOutbox(contactId = "ct-2", opType = OutboxEntity.OpType.CREATE))

        val all = outboxDao.listReady(now = 2_000_000_000L)
        assertEquals(2, all.size)

        outboxDao.quarantine(all[0].id, "409 conflict")
        val afterQuarantine = outboxDao.listReady(now = 2_000_000_000L)
        assertEquals(1, afterQuarantine.size)
        assertEquals("ct-2", afterQuarantine[0].protonContactId)
    }

    @Test fun outbox_list_ready_excludes_entries_not_yet_due() = runTest {
        outboxDao.insert(
            sampleOutbox(contactId = "ct-1", opType = OutboxEntity.OpType.UPDATE)
                .copy(nextAttemptAt = 3_000_000_000L)
        )

        val ready = outboxDao.listReady(now = 2_000_000_000L)
        assertTrue(ready.isEmpty())

        val readyLater = outboxDao.listReady(now = 3_000_000_001L)
        assertEquals(1, readyLater.size)
    }

    @Test fun outbox_record_failure_updates_attempts_and_next_attempt() = runTest {
        outboxDao.insert(sampleOutbox(contactId = "ct-1", opType = OutboxEntity.OpType.UPDATE))
        val entry = outboxDao.listReady(now = 2_000_000_000L).single()

        outboxDao.recordFailure(
            id = entry.id,
            attempts = 1,
            error = "503 Service Unavailable",
            nextAt = 1_700_030_000L
        )

        val updated = outboxDao.findByContact("ct-1").single()
        assertEquals(1, updated.attempts)
        assertEquals("503 Service Unavailable", updated.lastError)
        assertEquals(1_700_030_000L, updated.nextAttemptAt)
    }

    @Test fun outbox_list_quarantined_returns_only_quarantined_entries_oldest_first() = runTest {
        outboxDao.insert(sampleOutbox(contactId = "ct-1", opType = OutboxEntity.OpType.UPDATE))
        outboxDao.insert(sampleOutbox(contactId = "ct-2", opType = OutboxEntity.OpType.CREATE))
        outboxDao.insert(sampleOutbox(contactId = "ct-3", opType = OutboxEntity.OpType.DELETE))
        val all = outboxDao.listReady(now = 2_000_000_000L)

        outboxDao.quarantine(all[2].id, "contact not found locally")
        outboxDao.quarantine(all[0].id, "HttpException: 422")

        val quarantined = outboxDao.listQuarantined()
        assertEquals(2, quarantined.size)
        assertEquals("ct-1", quarantined[0].protonContactId)
        assertEquals("HttpException: 422", quarantined[0].lastError)
        assertEquals("ct-3", quarantined[1].protonContactId)
        assertEquals("contact not found locally", quarantined[1].lastError)
    }

    @Test fun outbox_requeue_clears_quarantine_and_resets_backoff() = runTest {
        outboxDao.insert(sampleOutbox(contactId = "ct-1", opType = OutboxEntity.OpType.UPDATE))
        val entry = outboxDao.listReady(now = 2_000_000_000L).single()
        outboxDao.recordFailure(entry.id, attempts = 4, error = "503", nextAt = 9_000_000_000L)
        outboxDao.quarantine(entry.id, "HttpException: 422")
        assertEquals(1, outboxDao.countQuarantined())

        outboxDao.requeue(entry.id)

        assertEquals(0, outboxDao.countQuarantined())
        assertEquals(1, outboxDao.countPending())
        val requeued = outboxDao.listReady(now = 2_000_000_000L).single()
        assertEquals(0, requeued.attempts)
        assertNull(requeued.lastError)
        assertEquals(0L, requeued.nextAttemptAt)
    }

    @Test fun outbox_requeue_ignores_entries_that_are_not_quarantined() = runTest {
        outboxDao.insert(sampleOutbox(contactId = "ct-1", opType = OutboxEntity.OpType.UPDATE))
        val entry = outboxDao.listReady(now = 2_000_000_000L).single()
        outboxDao.recordFailure(entry.id, attempts = 2, error = "503", nextAt = 9_000_000_000L)

        outboxDao.requeue(entry.id)

        val untouched = outboxDao.findByContact("ct-1").single()
        assertEquals(2, untouched.attempts)
        assertEquals("503", untouched.lastError)
        assertEquals(9_000_000_000L, untouched.nextAttemptAt)
    }

    @Test fun outbox_delete_by_contact_removes_all_entries_for_that_contact() = runTest {
        outboxDao.insert(sampleOutbox(contactId = "ct-1", opType = OutboxEntity.OpType.UPDATE))
        outboxDao.insert(sampleOutbox(contactId = "ct-1", opType = OutboxEntity.OpType.DELETE))
        outboxDao.insert(sampleOutbox(contactId = "ct-2", opType = OutboxEntity.OpType.UPDATE))

        outboxDao.deleteByContact("ct-1")

        assertTrue(outboxDao.findByContact("ct-1").isEmpty())
        assertEquals(1, outboxDao.findByContact("ct-2").size)
    }

    @Test fun outbox_delete_all_clears_entire_table() = runTest {
        outboxDao.insert(sampleOutbox(contactId = "ct-1", opType = OutboxEntity.OpType.CREATE))
        outboxDao.insert(sampleOutbox(contactId = "ct-2", opType = OutboxEntity.OpType.UPDATE))
        outboxDao.insert(sampleOutbox(contactId = "ct-3", opType = OutboxEntity.OpType.DELETE))

        outboxDao.deleteAll()

        assertEquals(0, outboxDao.countPending())
        assertEquals(0, outboxDao.countQuarantined())
    }

    @Test fun outbox_count_pending_excludes_quarantined() = runTest {
        outboxDao.insert(sampleOutbox(contactId = "ct-1", opType = OutboxEntity.OpType.UPDATE))
        outboxDao.insert(sampleOutbox(contactId = "ct-2", opType = OutboxEntity.OpType.CREATE))
        assertEquals(2, outboxDao.countPending())

        val first = outboxDao.listReady(now = 2_000_000_000L).first()
        outboxDao.quarantine(first.id, "permanent failure")
        assertEquals(1, outboxDao.countPending())
        assertEquals(1, outboxDao.countQuarantined())
    }

    @Test fun outbox_list_pending_deletes_returns_only_non_quarantined_deletes() = runTest {
        outboxDao.insert(sampleOutbox(contactId = "ct-1", opType = OutboxEntity.OpType.DELETE))
        outboxDao.insert(sampleOutbox(contactId = "ct-2", opType = OutboxEntity.OpType.UPDATE))
        outboxDao.insert(sampleOutbox(contactId = "ct-3", opType = OutboxEntity.OpType.DELETE))

        val deletes = outboxDao.listPendingDeletes()
        assertEquals(2, deletes.size)
        assertTrue(deletes.all { it.opType == OutboxEntity.OpType.DELETE })

        outboxDao.quarantine(deletes[0].id, "permanent")
        assertEquals(1, outboxDao.listPendingDeletes().size)
    }

    @Test fun outbox_delete_by_id_removes_single_entry() = runTest {
        outboxDao.insert(sampleOutbox(contactId = "ct-1", opType = OutboxEntity.OpType.UPDATE))
        outboxDao.insert(sampleOutbox(contactId = "ct-1", opType = OutboxEntity.OpType.DELETE))

        val entries = outboxDao.findByContact("ct-1")
        assertEquals(2, entries.size)

        outboxDao.deleteById(entries[0].id)
        assertEquals(1, outboxDao.findByContact("ct-1").size)
    }

    @Test fun outbox_list_ready_orders_by_created_at() = runTest {
        outboxDao.insert(
            sampleOutbox(contactId = "ct-newer", opType = OutboxEntity.OpType.UPDATE)
                .copy(createdAt = 1_700_000_200L)
        )
        outboxDao.insert(
            sampleOutbox(contactId = "ct-older", opType = OutboxEntity.OpType.CREATE)
                .copy(createdAt = 1_700_000_100L)
        )

        val ready = outboxDao.listReady(now = 2_000_000_000L)
        assertEquals("ct-older", ready[0].protonContactId)
        assertEquals("ct-newer", ready[1].protonContactId)
    }

    // --- helpers ---

    private fun sampleContact(
        id: String,
        rawId: Long,
        uid: String? = null,
        hash: String = "hash-$id",
        verified: Boolean = true
    ) = ContactMapEntity(
        protonContactId = id,
        protonUid = uid,
        androidRawContactId = rawId,
        modifyTime = 1_700_000_000L,
        contentHash = hash,
        isVerified = verified,
        deleted = false,
        syncStatus = ContactMapEntity.Status.CLEAN,
        lastError = null,
        lastSyncedAt = 1_700_000_001L
    )

    private fun sampleOutbox(
        contactId: String,
        opType: Int
    ) = OutboxEntity(
        protonContactId = contactId,
        opType = opType,
        payloadHash = "hash-$contactId",
        createdAt = 1_700_000_000L
    )
}
