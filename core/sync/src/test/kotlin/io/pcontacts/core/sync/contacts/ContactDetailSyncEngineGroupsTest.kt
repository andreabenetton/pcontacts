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
import org.junit.Assert.assertNull
import org.junit.Test

/** The pull's side of contact groups (ADR-0027): the stored base, and label changes ModifyTime misses. */
class ContactDetailSyncEngineGroupsTest {

    private val account = Account("alice@proton.me", "io.pcontacts.account")
    private val rowOf = mapOf("L-a" to 11L, "L-b" to 12L)

    private val labels = object : ProtonLabelsApi {
        override suspend fun listLabels(type: Int) = GetLabelsResponse(
            code = 1000,
            labels = listOf(LabelDto(id = "L-a", name = "A"), LabelDto(id = "L-b", name = "B"))
        )
    }

    private fun labelled(modifyTime: Long, vararg labelIds: String) =
        ContactMetadataDto(id = "c1", modifyTime = modifyTime, labelIds = labelIds.toList())

    private fun api(vararg pages: ContactMetadataDto) = DetailFakeApi(
        metadataPages = pages.map { metaPage(it) },
        contacts = mapOf("c1" to contact("c1", 100L, aliceVCard)),
        repeatContacts = true
    )

    private fun engine(
        api: DetailFakeApi,
        dao: DetailFakeContactMapDao,
        applier: DetailFakeApplier,
        phoneGroups: List<Long> = emptyList()
    ) = newEngine(
        api,
        dao,
        applier,
        labelsApi = labels,
        reconcileGroups = { _, _ -> rowOf },
        readGroupRowIds = { phoneGroups }
    )

    @Test fun a_pull_stores_protons_labels_sorted_and_writes_the_groups_sorted() = runTest {
        val dao = DetailFakeContactMapDao()
        val applier = DetailFakeApplier(base = 1L)

        engine(api(labelled(100L, "L-b", "L-a")), dao, applier).sync(account)

        assertEquals("L-a,L-b", dao.findByProtonId("c1")!!.serverLabelIds)
        val written = applier.lastIntents.filterIsInstance<RawContactOpIntent.CreateContact>().single().row
        assertEquals(listOf(11L, 12L), written.groupRowIds)
    }

    @Test fun a_label_change_without_a_new_modify_time_is_fetched_again() = runTest {
        val api = api(labelled(100L, "L-a"), labelled(100L, "L-a", "L-b"))
        val dao = DetailFakeContactMapDao()
        val applier = DetailFakeApplier(base = 1L)
        val engine = engine(api, dao, applier)
        engine.sync(account)
        val fetched = api.getContactCallCount

        engine.sync(account)

        assertEquals(fetched + 1, api.getContactCallCount)
        assertEquals("L-a,L-b", dao.findByProtonId("c1")!!.serverLabelIds)
    }

    @Test fun an_unknown_base_is_filled_in_without_a_fetch_when_the_phone_already_matches() = runTest {
        val api = api(labelled(100L, "L-a"), labelled(100L, "L-a"))
        val dao = DetailFakeContactMapDao()
        val applier = DetailFakeApplier(base = 1L)
        val engine = engine(api, dao, applier, phoneGroups = listOf(11L))
        engine.sync(account)
        dao.setServerLabelIds("c1", null) // the first pull after the upgrade
        val fetched = api.getContactCallCount

        engine.sync(account)

        assertEquals(fetched, api.getContactCallCount)
        assertEquals("L-a", dao.findByProtonId("c1")!!.serverLabelIds)
    }

    @Test fun an_unknown_base_with_different_phone_groups_rewrites_the_contact() = runTest {
        val api = api(labelled(100L, "L-a"), labelled(100L, "L-a"))
        val dao = DetailFakeContactMapDao()
        val applier = DetailFakeApplier(base = 1L)
        val engine = engine(api, dao, applier, phoneGroups = emptyList())
        engine.sync(account)
        dao.setServerLabelIds("c1", null)
        val fetched = api.getContactCallCount

        engine.sync(account)

        assertEquals(fetched + 1, api.getContactCallCount)
        assertEquals("L-a", dao.findByProtonId("c1")!!.serverLabelIds)
    }

    @Test fun labels_that_cannot_be_read_leave_the_base_unknown() = runTest {
        val failing = object : ProtonLabelsApi {
            override suspend fun listLabels(type: Int): GetLabelsResponse = error("labels down")
        }
        val dao = DetailFakeContactMapDao()

        newEngine(api(labelled(100L, "L-a")), dao, DetailFakeApplier(base = 1L), labelsApi = failing).sync(account)

        assertNull(dao.findByProtonId("c1")!!.serverLabelIds)
    }
}
