// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.sync.contacts

import android.accounts.Account
import io.pcontacts.core.contactswriter.RawContactOpIntent
import io.pcontacts.core.proton.api.contacts.ContactMetadataDto
import io.pcontacts.core.proton.api.labels.GetLabelsResponse
import io.pcontacts.core.proton.api.labels.LabelDto
import io.pcontacts.core.proton.api.labels.ProtonLabelsApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

/**
 * Data-integrity cases from the second v2.0.0 review: a Labels outage
 * must not erase group memberships (H3) and a contact that loses its
 * last reachable field must still update the local row (H6).
 */
class ContactDetailSyncEngineIntegrityTest {

    private val account = Account("user@proton.me", "io.pcontacts.account")

    /** A Labels API that can be switched off between rounds. */
    private class FlakyLabelsApi : ProtonLabelsApi {
        var down = false
        override suspend fun listLabels(type: Int): GetLabelsResponse {
            if (down) throw IOException("labels down")
            return GetLabelsResponse(code = 1000, labels = listOf(LabelDto(id = "L1", name = "Family")))
        }
    }

    @Test fun a_labels_outage_keeps_group_memberships_and_converges_once_labels_return() = runTest {
        val labels = FlakyLabelsApi()
        val page = { modifyTime: Long ->
            metaPage(ContactMetadataDto(id = "c1", modifyTime = modifyTime, labelIds = listOf("L1")))
        }
        val vcard = { tel: String -> "BEGIN:VCARD\nVERSION:4.0\nFN:Alice\nTEL:$tel\nEND:VCARD" }
        val api = DetailFakeApi(
            metadataPages = listOf(page(100L), page(200L), page(200L)),
            contacts = mapOf("c1" to contact("c1", 100L, vcard("+1"))),
            secondRoundContacts = mapOf("c1" to contact("c1", 200L, vcard("+2")))
        )
        val dao = DetailFakeContactMapDao()
        val applier = DetailFakeApplier(base = 1L)
        // The provider keeps whatever memberships were last written.
        var providerGroups: List<Long> = emptyList()
        val engine = newEngine(
            api,
            dao,
            applier,
            labelsApi = labels,
            reconcileGroups = { _, list -> list.associate { it.id to 7L } },
            readGroupRowIds = { providerGroups }
        )

        // Round 1: labels work, the contact joins Family (local group 7).
        engine.sync(account)
        val created = applier.lastIntents.filterIsInstance<RawContactOpIntent.CreateContact>().single()
        assertEquals(listOf(7L), created.row.groupRowIds)
        providerGroups = created.row.groupRowIds
        assertEquals(100L, dao.snapshot()["c1"]!!.modifyTime)

        // Round 2: the server changed the phone while the Labels API is down.
        labels.down = true
        engine.sync(account)
        val updated = applier.lastIntents.filterIsInstance<RawContactOpIntent.UpdateContact>().single()
        assertEquals("Family membership must survive the outage", listOf(7L), updated.row.groupRowIds)
        assertEquals("the contact is marked for a refetch", 0L, dao.snapshot()["c1"]!!.modifyTime)
        val callsAfterOutage = applier.applyCallCount

        // Round 3: labels are back, nothing changed on the server.
        labels.down = false
        engine.sync(account)
        assertEquals(200L, dao.snapshot()["c1"]!!.modifyTime)
        assertEquals("nothing to rewrite once the state agrees", callsAfterOutage, applier.applyCallCount)
    }

    @Test fun when_the_server_drops_the_last_email_the_local_row_is_updated_not_left_stale() = runTest {
        val api = DetailFakeApi(
            metadataPages = listOf(metaPage(meta("c1", 100L)), metaPage(meta("c1", 200L))),
            contacts = mapOf("c1" to contact("c1", 100L, aliceVCard)),
            secondRoundContacts = mapOf(
                "c1" to contact("c1", 200L, "BEGIN:VCARD\nVERSION:4.0\nFN:Alice\nNOTE:name only now\nEND:VCARD")
            )
        )
        val dao = DetailFakeContactMapDao()
        val applier = DetailFakeApplier(base = 1L)
        val engine = newEngine(api, dao, applier)

        engine.sync(account)
        val rawId = applier.rawIdsFor("c1").single()
        val second = engine.sync(account)

        assertEquals(1, second.updated)
        assertEquals(0, second.failed)
        val update = applier.lastIntents.filterIsInstance<RawContactOpIntent.UpdateContact>().single()
        assertEquals(rawId, update.rawContactId)
        assertEquals("Alice", update.row.displayName)
        assertEquals(emptyList<String>(), update.row.emails)
        assertEquals(listOf("name only now"), update.row.notes)
        assertEquals(200L, dao.snapshot()["c1"]!!.modifyTime)
    }
}
