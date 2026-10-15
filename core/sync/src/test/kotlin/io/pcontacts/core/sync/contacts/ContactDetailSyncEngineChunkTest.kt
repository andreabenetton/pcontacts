// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.sync.contacts

import android.accounts.Account
import io.pcontacts.core.contactswriter.RawContactOpIntent
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
