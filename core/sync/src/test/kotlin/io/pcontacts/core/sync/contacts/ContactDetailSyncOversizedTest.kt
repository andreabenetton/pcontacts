// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.sync.contacts

import android.accounts.Account
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A contact too large to hand to the phone's contacts is left out of the write (ADR-0010,
 * 2026-11-18): the pull records the others and reports that one as failed (review 2026-11-18).
 */
class ContactDetailSyncOversizedTest {

    private val account = Account("alice@proton.me", "io.pcontacts.account")

    private fun card(name: String) = "BEGIN:VCARD\nVERSION:4.0\nFN:$name\nEMAIL:${name.lowercase()}@proton.me\nEND:VCARD"

    private fun threeContacts() = DetailFakeApi(
        metadataPages = listOf(
            metaPage(meta("c1", 100L), meta("c2", 100L), meta("c3", 100L)),
            metaPage(meta("c1", 100L), meta("c2", 100L), meta("c3", 100L))
        ),
        contacts = mapOf(
            "c1" to contact("c1", 100L, card("Alice")),
            "c2" to contact("c2", 100L, card("Bob")),
            "c3" to contact("c3", 100L, card("Carol"))
        ),
        repeatContacts = true
    )

    @Test fun the_contacts_around_one_too_large_are_written_and_it_counts_as_failed() = runTest {
        val dao = DetailFakeContactMapDao()
        val applier = DetailFakeApplier(base = 1L).apply { tooLargeSourceIds += "c2" }

        val report = newEngine(threeContacts(), dao, applier).sync(account)

        assertEquals(2, report.inserted)
        assertEquals(1, report.failed)
        assertNotNull(dao.snapshot()["c1"])
        assertNotNull(dao.snapshot()["c3"])
        assertNull("not recorded as synced", dao.snapshot()["c2"])
        assertEquals(emptyList<Long>(), applier.rawIdsFor("c2"))
    }

    @Test fun the_one_too_large_is_tried_again_on_the_next_run() = runTest {
        val api = threeContacts()
        val dao = DetailFakeContactMapDao()
        val applier = DetailFakeApplier(base = 1L).apply { tooLargeSourceIds += "c2" }
        val engine = newEngine(api, dao, applier)
        engine.sync(account)
        applier.tooLargeSourceIds.clear()

        val report = engine.sync(account)

        assertEquals(1, report.inserted)
        assertEquals(0, report.failed)
        assertNotNull(dao.snapshot()["c2"])
    }
}
