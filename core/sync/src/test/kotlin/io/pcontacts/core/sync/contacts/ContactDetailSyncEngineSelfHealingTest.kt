// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.sync.contacts

import android.accounts.Account
import io.pcontacts.core.contactswriter.RawContactOpIntent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression suite for the self-healing invariant (ADR-0022):
 * ContactsProvider is authoritative for which local RawContacts exist;
 * Room only stores sync metadata and must converge to provider reality.
 * Covers missing-row recovery, stale-mapping repair, duplicate
 * SOURCE_ID reconciliation, intentional-deletion protection, and
 * idempotency.
 */
class ContactDetailSyncEngineSelfHealingTest {

    private val account = Account("alice@proton.me", "io.pcontacts.account")

    @Test fun mapped_contact_with_missing_raw_contact_is_recreated_even_when_server_unchanged() = runTest {
        // The Mudita field case: an external duplicate-cleanup pass purged
        // our RawContact. Room still holds a mapping with an up-to-date
        // modifyTime and hash — the cheap-skip must not fire.
        val api = DetailFakeApi(
            metadataPages = listOf(
                metaPage(meta("c1", 100L)),
                metaPage(meta("c1", 100L)) // server unchanged
            ),
            contacts = mapOf("c1" to contact("c1", 100L, aliceVCard)),
            repeatContacts = true
        )
        val dao = DetailFakeContactMapDao()
        val applier = DetailFakeApplier(base = 1000L)
        val engine = newEngine(api, dao, applier)
        engine.sync(account)
        applier.removeRawContact("c1")
        val fetchesAfterFirstRun = api.getContactCallCount

        val report = engine.sync(account)

        assertEquals("missing RawContact must be recreated", 1, report.inserted)
        assertTrue("recovery requires a re-fetch", api.getContactCallCount > fetchesAfterFirstRun)
        assertEquals("exactly one row afterwards", 1, applier.rawIdsFor("c1").size)
        assertEquals(
            "mapping must point at the recreated row",
            applier.rawIdsFor("c1").single(),
            dao.snapshot()["c1"]!!.androidRawContactId
        )
    }

    @Test fun hash_match_does_not_suppress_recreation_when_raw_contact_is_missing() = runTest {
        // modifyTime bumped → fetch happens; content identical → hash
        // matches the stored one. A matching hash means "data unchanged",
        // not "the local row still exists".
        val api = DetailFakeApi(
            metadataPages = listOf(
                metaPage(meta("c1", 100L)),
                metaPage(meta("c1", 200L))
            ),
            contacts = mapOf("c1" to contact("c1", 100L, aliceVCard)),
            secondRoundContacts = mapOf("c1" to contact("c1", 200L, aliceVCard))
        )
        val dao = DetailFakeContactMapDao()
        val applier = DetailFakeApplier(base = 1000L)
        val engine = newEngine(api, dao, applier)
        engine.sync(account)
        applier.removeRawContact("c1")

        val report = engine.sync(account)

        assertEquals("hash-skip must not suppress the recreate", 1, report.inserted)
        assertEquals(1, applier.rawIdsFor("c1").size)
        assertEquals(200L, dao.snapshot()["c1"]!!.modifyTime)
    }

    @Test fun stale_mapping_raw_contact_id_is_repaired_without_rewriting_contact_data() = runTest {
        val api = DetailFakeApi(
            metadataPages = listOf(
                metaPage(meta("c1", 100L)),
                metaPage(meta("c1", 100L))
            ),
            contacts = mapOf("c1" to contact("c1", 100L, aliceVCard)),
            repeatContacts = true
        )
        val dao = DetailFakeContactMapDao()
        val applier = DetailFakeApplier(base = 1000L)
        val engine = newEngine(api, dao, applier)
        engine.sync(account)
        // Provider now holds the contact under a different _ID than the
        // mapping remembers (e.g. an external remove+recreate).
        applier.removeRawContact("c1")
        applier.seedRow("c1", 2000L)

        val report = engine.sync(account)

        assertEquals("no rewrite for unchanged content", 0, report.inserted + report.updated)
        assertEquals("no second apply call", 1, applier.applyCallCount)
        assertEquals("mapping converges to provider reality", 2000L, dao.snapshot()["c1"]!!.androidRawContactId)
        assertEquals(listOf(2000L), applier.rawIdsFor("c1"))
    }

    @Test fun duplicate_raw_contacts_are_reconciled_to_the_mapped_survivor_without_proton_delete() = runTest {
        val api = DetailFakeApi(
            metadataPages = listOf(
                metaPage(meta("c1", 100L)),
                metaPage(meta("c1", 100L))
            ),
            contacts = mapOf("c1" to contact("c1", 100L, aliceVCard)),
            repeatContacts = true
        )
        val dao = DetailFakeContactMapDao()
        val applier = DetailFakeApplier(base = 1000L)
        val engine = newEngine(api, dao, applier)
        engine.sync(account)
        // Invalid provider state: three rows sharing SOURCE_ID c1.
        applier.seedRow("c1", 1001L)
        applier.seedRow("c1", 1002L)

        val report = engine.sync(account)

        assertEquals("dedup is not a server deletion", 0, report.deleted)
        assertEquals(
            "only the two extras are deleted, by row id",
            setOf(1001L, 1002L),
            applier.lastIntents.filterIsInstance<RawContactOpIntent.DeleteRawContact>()
                .map { it.rawContactId }.toSet()
        )
        assertTrue(
            "no sourceId-level delete (would purge the survivor / imply a Proton delete)",
            applier.lastIntents.none { it is RawContactOpIntent.DeleteContact }
        )
        assertEquals("the mapped row survives", listOf(1000L), applier.rawIdsFor("c1"))
        assertEquals(1000L, dao.snapshot()["c1"]!!.androidRawContactId)
    }

    @Test fun pending_local_delete_blocks_self_healing_resurrection() = runTest {
        val api = DetailFakeApi(
            metadataPages = listOf(
                metaPage(meta("c1", 100L)),
                metaPage(meta("c1", 100L))
            ),
            contacts = mapOf("c1" to contact("c1", 100L, aliceVCard)),
            repeatContacts = true
        )
        val dao = DetailFakeContactMapDao()
        val applier = DetailFakeApplier(base = 1000L)
        val engine = newEngine(api, dao, applier, hasPendingDelete = { it == "c1" })
        engine.sync(account)
        // User deleted locally; the tombstone got purged externally but the
        // outbox DELETE is still queued (grace period).
        applier.removeRawContact("c1")
        val fetchesAfterFirstRun = api.getContactCallCount

        val report = engine.sync(account)

        assertEquals("must not resurrect a pending deletion", 0, report.inserted)
        assertEquals("no fetch for a contact awaiting delete push", fetchesAfterFirstRun, api.getContactCallCount)
        assertTrue(applier.rawIdsFor("c1").isEmpty())
    }

    @Test fun tombstoned_raw_contact_counts_as_present_and_is_not_recreated() = runTest {
        val api = DetailFakeApi(
            metadataPages = listOf(
                metaPage(meta("c1", 100L)),
                metaPage(meta("c1", 100L))
            ),
            contacts = mapOf("c1" to contact("c1", 100L, aliceVCard)),
            repeatContacts = true
        )
        val dao = DetailFakeContactMapDao()
        val applier = DetailFakeApplier(base = 1000L)
        val engine = newEngine(api, dao, applier)
        engine.sync(account)
        // User deletion mid-grace-period: DELETED=1 tombstone still present.
        applier.removeRawContact("c1")
        applier.seedRow("c1", 1000L, deleted = true)

        val report = engine.sync(account)

        assertEquals("tombstone must not be treated as missing", 0, report.inserted)
        assertEquals("no second apply call", 1, applier.applyCallCount)
    }

    @Test fun self_healing_and_dedup_are_idempotent_across_runs() = runTest {
        val api = DetailFakeApi(
            metadataPages = listOf(
                metaPage(meta("c1", 100L)),
                metaPage(meta("c1", 100L)),
                metaPage(meta("c1", 100L))
            ),
            contacts = mapOf("c1" to contact("c1", 100L, aliceVCard)),
            repeatContacts = true
        )
        val dao = DetailFakeContactMapDao()
        val applier = DetailFakeApplier(base = 1000L)
        val engine = newEngine(api, dao, applier)
        engine.sync(account)
        applier.seedRow("c1", 1001L)

        engine.sync(account)
        val callsAfterDedupRun = applier.applyCallCount
        val report = engine.sync(account)

        assertEquals("second reconciliation run must be a no-op", callsAfterDedupRun, applier.applyCallCount)
        assertEquals(SyncReport(totalServer = 1, inserted = 0, updated = 0, deleted = 0, unchanged = 1), report)
        assertEquals(listOf(1000L), applier.rawIdsFor("c1"))
        assertEquals(1000L, dao.snapshot()["c1"]!!.androidRawContactId)
    }
}
