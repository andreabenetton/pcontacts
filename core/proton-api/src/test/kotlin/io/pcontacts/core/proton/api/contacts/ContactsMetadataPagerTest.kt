// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.proton.api.contacts

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Mirror-image tests of ContactEmailsPagerTest — same pagination
 * semantics, different endpoint.
 */
class ContactsMetadataPagerTest {

    @Test fun stitches_full_pages_then_stops_on_partial_last() = runTest {
        val fake = FakeMetadataApi(
            ContactsPageResponse(code = 1000, contacts = (1..3).map { meta("c$it", it.toLong() * 100) }),
            ContactsPageResponse(code = 1000, contacts = (4..6).map { meta("c$it", it.toLong() * 100) }),
            ContactsPageResponse(code = 1000, contacts = listOf(meta("c7", 700L)))
        )
        val pager = ContactsMetadataPager(api = fake, pageSize = 3)
        val collected = pager.metadata().toList()
        assertEquals((1..7).map { "c$it" }, collected.map { it.id })
        assertEquals(700L, collected.last().modifyTime)
        assertEquals(listOf(0, 1, 2), fake.calls.map { it.page })
    }

    @Test fun stops_on_empty_first_page() = runTest {
        val fake = FakeMetadataApi(ContactsPageResponse(code = 1000))
        val pager = ContactsMetadataPager(api = fake, pageSize = 100)
        assertEquals(emptyList<ContactMetadataDto>(), pager.metadata().toList())
        assertEquals(1, fake.calls.size)
    }

    @Test fun forwards_labelId_filter_to_api() = runTest {
        val fake = FakeMetadataApi(ContactsPageResponse(code = 1000))
        ContactsMetadataPager(api = fake, pageSize = 50)
            .metadata(labelIdFilter = "label-1")
            .toList()
        assertEquals("label-1", fake.calls[0].labelIdFilter)
        assertNull(fake.calls[0].emailFilter)
    }

    private fun meta(id: String, modifyTime: Long) =
        ContactMetadataDto(id = id, modifyTime = modifyTime)

    private class FakeMetadataApi(vararg responses: ContactsPageResponse) : ProtonContactsApi {
        data class Call(val page: Int, val pageSize: Int, val emailFilter: String?, val labelIdFilter: String?)
        val calls = mutableListOf<Call>()
        private val queue = ArrayDeque(responses.toList())

        override suspend fun listContacts(
            page: Int,
            pageSize: Int,
            labelIdFilter: String?
        ): ContactsPageResponse {
            calls += Call(page, pageSize, emailFilter = null, labelIdFilter = labelIdFilter)
            return if (queue.isEmpty()) ContactsPageResponse(code = 1000) else queue.removeFirst()
        }

        override suspend fun listContactEmails(
            page: Int,
            pageSize: Int,
            emailFilter: String?,
            labelIdFilter: String?
        ): ContactEmailsPageResponse =
            error("not used in metadata pager tests")

        override suspend fun getContact(id: String): GetContactResponse =
            error("not used in metadata pager tests")

        override suspend fun createContacts(request: CreateContactsRequest): CreateContactsResponse =
            error("not used in metadata pager tests")

        override suspend fun updateContact(id: String, request: UpdateContactRequest): UpdateContactResponse =
            error("not used in metadata pager tests")

        override suspend fun deleteContacts(request: BulkDeleteRequest): BulkDeleteResponse =
            error("not used in metadata pager tests")
    }
}
