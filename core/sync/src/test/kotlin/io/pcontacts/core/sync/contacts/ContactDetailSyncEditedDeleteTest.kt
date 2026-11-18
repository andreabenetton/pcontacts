// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.sync.contacts

import android.accounts.Account
import io.pcontacts.core.storage.db.entity.ContactMapEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A contact gone from Proton whose row was edited on the phone after the run last looked at
 * it: the provider's delete leaves the row (ADR-0010, 2026-11-18), and the pull must not
 * forget the contact (review 2026-11-18, R2).
 */
class ContactDetailSyncEditedDeleteTest {

    private val account = Account("alice@proton.me", "io.pcontacts.account")

    private fun card(name: String) = "BEGIN:VCARD\nVERSION:4.0\nFN:$name\nEMAIL:${name.lowercase()}@proton.me\nEND:VCARD"

    private fun twoContactsThenOnlyTheFirst() = DetailFakeApi(
        metadataPages = listOf(
            metaPage(meta("c1", 100L), meta("c2", 100L)),
            metaPage(meta("c1", 100L)),
            metaPage(meta("c1", 100L))
        ),
        contacts = mapOf("c1" to contact("c1", 100L, card("Alice")), "c2" to contact("c2", 100L, card("Bob"))),
        repeatContacts = true
    )

    @Test fun a_row_edited_while_the_pull_ran_is_kept_with_its_mapping() = runTest {
        val api = twoContactsThenOnlyTheFirst()
        val dao = DetailFakeContactMapDao()
        val applier = DetailFakeApplier(base = 1L)
        val engine = newEngine(api, dao, applier)
        engine.sync(account)
        api.goneIds += "c2"
        // The user adds a number to Bob after the push looked for changes: no outbox entry yet.
        applier.editedRawIds += applier.rawIdsFor("c2").single()

        val report = engine.sync(account)

        assertEquals("Bob's row stays", 1, applier.rawIdsFor("c2").size)
        assertNotNull("and so does his mapping, or the edit would belong to nothing", dao.snapshot()["c2"])
        assertEquals(0, report.deleted)
    }

    @Test fun the_next_run_offers_the_kept_edit_as_a_conflict_with_the_deletion() = runTest {
        val api = twoContactsThenOnlyTheFirst()
        val dao = DetailFakeContactMapDao()
        val applier = DetailFakeApplier(base = 1L)
        var queued = false
        val engine = newEngine(api, dao, applier, hasLocalMutation = { it == "c2" && queued })
        engine.sync(account)
        api.goneIds += "c2"
        applier.editedRawIds += applier.rawIdsFor("c2").single()
        engine.sync(account)
        // The next run's push finds the dirty row and queues it; the row is then clean again.
        queued = true
        applier.editedRawIds.clear()

        engine.sync(account)

        val mapping = dao.snapshot()["c2"]!!
        assertEquals(ContactMapEntity.Status.CONFLICT, mapping.syncStatus)
        assertEquals(SERVER_DELETED_CONFLICT, mapping.lastError)
        assertEquals(1, applier.rawIdsFor("c2").size)
    }

    @Test fun a_row_nobody_edited_is_deleted_beside_one_that_was() = runTest {
        val api = DetailFakeApi(
            metadataPages = listOf(metaPage(meta("c1", 100L), meta("c2", 100L)), metaPage()),
            contacts = mapOf("c1" to contact("c1", 100L, card("Alice")), "c2" to contact("c2", 100L, card("Bob")))
        )
        val dao = DetailFakeContactMapDao()
        val applier = DetailFakeApplier(base = 1L)
        val engine = newEngine(api, dao, applier)
        engine.sync(account)
        api.goneIds += listOf("c1", "c2")
        applier.editedRawIds += applier.rawIdsFor("c2").single()

        val report = engine.sync(account)

        assertEquals(1, report.deleted)
        assertNull(dao.snapshot()["c1"])
        assertEquals(emptyList<Long>(), applier.rawIdsFor("c1"))
        assertNotNull(dao.snapshot()["c2"])
    }

    @Test fun a_duplicate_edited_while_the_pull_ran_is_not_removed() = runTest {
        val api = DetailFakeApi(
            metadataPages = listOf(metaPage(meta("c1", 100L)), metaPage(meta("c1", 100L))),
            contacts = mapOf("c1" to contact("c1", 100L, card("Alice"))),
            repeatContacts = true
        )
        val dao = DetailFakeContactMapDao()
        val applier = DetailFakeApplier(base = 1L)
        val engine = newEngine(api, dao, applier)
        engine.sync(account)
        applier.seedRow("c1", rawId = 50L)
        applier.editedRawIds += 50L

        engine.sync(account)

        assertEquals(listOf(1L, 50L), applier.rawIdsFor("c1").sorted())
    }
}
