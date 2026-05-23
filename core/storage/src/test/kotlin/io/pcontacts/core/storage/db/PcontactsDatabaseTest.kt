// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.storage.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.pcontacts.core.storage.db.dao.ContactMapDao
import io.pcontacts.core.storage.db.dao.GroupMapDao
import io.pcontacts.core.storage.db.dao.SyncStateDao
import io.pcontacts.core.storage.db.entity.ContactMapEntity
import io.pcontacts.core.storage.db.entity.GroupMapEntity
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
 * Round-trip validation for the v1 schema. Runs under Robolectric so we
 * don't need an emulator — the trade-off is that we don't exercise
 * MigrationTestHelper here (there are no migrations yet). The schema
 * JSON ksp emits under `:core:storage/schemas/` is the future input for
 * MigrationTestHelper-driven tests once v2 ships.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class PcontactsDatabaseTest {

    private lateinit var db: PcontactsDatabase
    private lateinit var contactMapDao: ContactMapDao
    private lateinit var groupMapDao: GroupMapDao
    private lateinit var syncStateDao: SyncStateDao

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = DatabaseFactory.createInMemory(context)
        contactMapDao = db.contactMapDao()
        groupMapDao = db.groupMapDao()
        syncStateDao = db.syncStateDao()
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

    private fun sampleContact(
        id: String,
        rawId: Long,
        uid: String? = null,
        hash: String = "hash-$id"
    ) = ContactMapEntity(
        protonContactId = id,
        protonUid = uid,
        androidRawContactId = rawId,
        modifyTime = 1_700_000_000L,
        contentHash = hash,
        isVerified = true,
        deleted = false,
        syncStatus = ContactMapEntity.Status.CLEAN,
        lastError = null,
        lastSyncedAt = 1_700_000_001L
    )
}
