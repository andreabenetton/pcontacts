// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.sync.contacts

import android.accounts.Account
import io.pcontacts.core.contactswriter.RawContactOpIntent
import io.pcontacts.core.proton.api.contacts.ContactMetadataDto
import io.pcontacts.core.storage.db.entity.ContactMapEntity
import io.pcontacts.core.storage.db.entity.OutboxEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The pull writes and records contacts in chunks, so a run the system stops keeps its progress (issue #70). */
class ContactDetailSyncEngineChunkTest {

    private val account = Account("alice@proton.me", "io.pcontacts.account")
    private val ids = (1..120).map { "c$it" }

    private fun api(): DetailFakeApi {
        val page = metaPage(*ids.map { meta(it, 100L) }.toTypedArray())
        return DetailFakeApi(
            metadataPages = listOf(page, page),
            contacts = ids.associateWith { contact(it, 100L, card(it)) },
            repeatContacts = true
        )
    }

    private fun card(id: String) = "BEGIN:VCARD\nVERSION:4.0\nFN:$id\nEMAIL:$id@x\nEND:VCARD"

    @Test fun contacts_are_written_and_recorded_chunk_by_chunk() = runTest {
        val dao = DetailFakeContactMapDao()
        val applier = DetailFakeApplier(base = 1L)

        newEngine(api(), dao, applier).sync(account)

        assertEquals("120 contacts in chunks of 50", 3, applier.applyCallCount)
        assertEquals(120, dao.countLive())
    }

    @Test fun a_row_edited_on_the_phone_during_the_run_is_left_for_the_next_run() = runTest {
        // Before, the pull's write replaced the edit and the next detect saw the server's version
        // (ADR-0017, 2026-10-15).
        val dao = DetailFakeContactMapDao()
        val applier = DetailFakeApplier(base = 1L)
        newEngine(api(), dao, applier).sync(account)
        val edited = dao.findByProtonId("c7")!!
        dao.forceRefetch("c7")

        newEngine(api(), dao, applier, readDirtyRawIds = { setOf(edited.androidRawContactId) }).sync(account)

        assertTrue(applier.lastIntents.none { it is RawContactOpIntent.UpdateContact && it.row.sourceId == "c7" })
        assertEquals("its mapping is left as it was", 0L, dao.findByProtonId("c7")!!.modifyTime)
    }

    @Test fun a_row_whose_create_is_still_queued_is_neither_rewritten_nor_recreated() = runTest {
        // The create's recording was cut short: the mapping exists, the row may or may not carry the
        // SOURCE_ID yet. The pull rewrote the row, or recreated the contact as a second row
        // (ADR-0017, 2026-10-15).
        val api = DetailFakeApi(
            metadataPages = listOf(metaPage(meta("srv-8", 200L)), metaPage(meta("srv-8", 200L))),
            contacts = mapOf("srv-8" to contact("srv-8", 200L, card("srv-8"))),
            repeatContacts = true
        )
        val dao = DetailFakeContactMapDao()
        dao.upsert(
            ContactMapEntity(
                protonContactId = "srv-8",
                protonUid = null,
                androidRawContactId = 105L,
                modifyTime = 0L,
                contentHash = "",
                isVerified = true,
                deleted = false,
                syncStatus = ContactMapEntity.Status.CLEAN,
                lastError = null,
                lastSyncedAt = 0L
            )
        )
        val applier = DetailFakeApplier(base = 1L)

        newEngine(api, dao, applier, queuedCreates = listOf(queuedCreate(105L))).sync(account)
        assertEquals("no second row for a contact whose row lacks its SOURCE_ID", 0, applier.applyCallCount)

        applier.seedRow("srv-8", 105L)
        newEngine(api, dao, applier, queuedCreates = listOf(queuedCreate(105L))).sync(account)
        assertEquals("the row is not rewritten", 0, applier.applyCallCount)
    }

    @Test fun the_contact_a_create_made_before_its_answer_was_lost_is_not_inserted_again() = runTest {
        // The POST committed, the answer was lost and the UID lookup failed: the CREATE is still
        // queued. The pull inserted Proton's copy as a second row (ADR-0017, 2026-10-15).
        val create = queuedCreate(105L)
        val page = metaPage(ContactMetadataDto(id = "srv-9", uid = create.createUid!!, modifyTime = 200L))
        val api = DetailFakeApi(
            metadataPages = listOf(page),
            contacts = mapOf("srv-9" to contact("srv-9", 200L, card("srv-9")))
        )
        val applier = DetailFakeApplier(base = 1L)

        newEngine(api, DetailFakeContactMapDao(), applier, queuedCreates = listOf(create)).sync(account)

        assertEquals(0, applier.applyCallCount)
    }

    private fun queuedCreate(rawId: Long) = OutboxEntity(
        protonContactId = "local-$rawId",
        opType = OutboxEntity.OpType.CREATE,
        payloadHash = "h",
        createdAt = 0L,
        createUid = "urn:uuid:minted-$rawId"
    )

    @Test fun a_contact_missing_from_the_listing_but_still_on_proton_is_not_deleted() = runTest {
        // [U] paging stability: a page that left a contact out deleted it on the phone
        // (ADR-0017, 2026-10-15).
        val full = metaPage(meta("a", 100L), meta("b", 100L))
        val api = DetailFakeApi(
            metadataPages = listOf(full, metaPage(meta("b", 100L))),
            contacts = mapOf("a" to contact("a", 100L, card("a")), "b" to contact("b", 100L, card("b"))),
            repeatContacts = true
        )
        val dao = DetailFakeContactMapDao()
        val applier = DetailFakeApplier(base = 1L)
        val engine = newEngine(api, dao, applier)
        engine.sync(account)

        val report = engine.sync(account)

        assertEquals(0, report.deleted)
        assertEquals(1, applier.rawIdsFor("a").size)
        assertNotNull(dao.findByProtonId("a"))
    }

    @Test fun a_run_stopped_midway_keeps_its_finished_chunks_and_the_next_run_carries_on() = runTest {
        val api = api().apply { stopAfterFetches = 60 } // inside the second chunk
        val dao = DetailFakeContactMapDao()
        val engine = newEngine(api, dao, DetailFakeApplier(base = 1L))

        try {
            engine.sync(account)
            fail("the run should have been stopped")
        } catch (expected: CancellationException) {
            // The system stopped the run.
        }
        assertEquals("the first chunk is recorded", 50, dao.countLive())

        api.stopAfterFetches = null
        val fetchedBefore = api.getContactCallCount
        engine.sync(account)

        assertEquals("only the contacts not recorded yet are fetched", 70, api.getContactCallCount - fetchedBefore)
        assertEquals(120, dao.countLive())
    }
}
