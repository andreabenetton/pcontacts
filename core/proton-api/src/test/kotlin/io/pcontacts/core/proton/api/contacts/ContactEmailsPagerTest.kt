// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.proton.api.contacts

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Pure-JVM tests against a tiny in-memory fake of the API. Avoids
 * MockWebServer here because the pager's contract is "what arguments do
 * I send to the API and what do I emit from the result" — the wire
 * protocol is exercised in ProtonContactsApiTest.
 */
class ContactEmailsPagerTest {

    @Test fun emits_single_page_when_server_returns_partial_first_page() = runTest {
        val fake = FakeContactsApi(
            ContactEmailsPageResponse(
                code = 1000,
                total = 2,
                contactEmails = listOf(email("e1", "c1"), email("e2", "c2"))
            )
        )
        val pager = ContactEmailsPager(api = fake, pageSize = 100)

        val collected = pager.emails().toList()
        assertEquals(listOf("e1", "e2"), collected.map { it.id })
        assertEquals(1, fake.calls.size)
        assertEquals(0, fake.calls[0].page)
    }

    @Test fun stitches_three_full_pages_then_stops_on_partial_last() = runTest {
        val fake = FakeContactsApi(
            ContactEmailsPageResponse(code = 1000, total = 7,
                contactEmails = (1..3).map { email("e$it", "c$it") }),
            ContactEmailsPageResponse(code = 1000, total = 7,
                contactEmails = (4..6).map { email("e$it", "c$it") }),
            ContactEmailsPageResponse(code = 1000, total = 7,
                contactEmails = listOf(email("e7", "c7")))
        )
        val pager = ContactEmailsPager(api = fake, pageSize = 3)

        val collected = pager.emails().toList()
        assertEquals((1..7).map { "e$it" }, collected.map { it.id })
        assertEquals(listOf(0, 1, 2), fake.calls.map { it.page })
        // Once the partial page (1 of 3) arrived, the pager must not ask for page 3.
        assertEquals(3, fake.calls.size)
    }

    @Test fun stops_on_empty_first_page() = runTest {
        val fake = FakeContactsApi(
            ContactEmailsPageResponse(code = 1000, total = 0, contactEmails = emptyList())
        )
        val pager = ContactEmailsPager(api = fake, pageSize = 100)

        assertEquals(emptyList<ContactEmailDto>(), pager.emails().toList())
        assertEquals(1, fake.calls.size)
    }

    @Test fun stops_on_empty_subsequent_page_even_if_previous_was_full() = runTest {
        // Edge case: server returns a full page, then suddenly an empty one
        // (e.g. a deletion mid-pagination). Pager must terminate cleanly.
        val fake = FakeContactsApi(
            ContactEmailsPageResponse(code = 1000, total = 3,
                contactEmails = (1..3).map { email("e$it", "c$it") }),
            ContactEmailsPageResponse(code = 1000, total = 3, contactEmails = emptyList())
        )
        val pager = ContactEmailsPager(api = fake, pageSize = 3)

        val collected = pager.emails().toList()
        assertEquals(listOf("e1", "e2", "e3"), collected.map { it.id })
        assertEquals(2, fake.calls.size)
    }

    @Test fun forwards_email_filter_unchanged_to_api() = runTest {
        val fake = FakeContactsApi(ContactEmailsPageResponse(code = 1000))
        ContactEmailsPager(api = fake, pageSize = 50)
            .emails(emailFilter = "alice@proton.me")
            .toList()
        assertEquals("alice@proton.me", fake.calls[0].emailFilter)
        assertNull(fake.calls[0].labelIdFilter)
    }

    @Test fun forwards_labelId_filter_unchanged_to_api() = runTest {
        val fake = FakeContactsApi(ContactEmailsPageResponse(code = 1000))
        ContactEmailsPager(api = fake, pageSize = 50)
            .emails(labelIdFilter = "label-1")
            .toList()
        assertNull(fake.calls[0].emailFilter)
        assertEquals("label-1", fake.calls[0].labelIdFilter)
    }

    @Test fun rejects_simultaneous_email_and_labelId_filters() = runTest {
        val fake = FakeContactsApi(ContactEmailsPageResponse(code = 1000))
        val pager = ContactEmailsPager(api = fake, pageSize = 50)
        val ex = assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                pager.emails(emailFilter = "x", labelIdFilter = "y").toList()
            }
        }
        assertEquals("Email and LabelID filters are mutually exclusive", ex.message)
    }

    @Test fun rejects_out_of_range_page_size_at_construction() {
        val fake = FakeContactsApi(ContactEmailsPageResponse(code = 1000))
        assertThrows(IllegalArgumentException::class.java) {
            ContactEmailsPager(api = fake, pageSize = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ContactEmailsPager(api = fake, pageSize = 1001)
        }
    }

    private fun email(id: String, contactId: String) = ContactEmailDto(
        id = id, email = "$id@proton.me", contactId = contactId
    )

    private class FakeContactsApi(vararg responses: ContactEmailsPageResponse) : ProtonContactsApi {
        data class Call(val page: Int, val pageSize: Int, val emailFilter: String?, val labelIdFilter: String?)
        val calls = mutableListOf<Call>()
        private val queue = ArrayDeque(responses.toList())

        override suspend fun listContactEmails(
            page: Int,
            pageSize: Int,
            emailFilter: String?,
            labelIdFilter: String?
        ): ContactEmailsPageResponse {
            calls += Call(page, pageSize, emailFilter, labelIdFilter)
            return if (queue.isEmpty()) {
                // Defensive: if the pager keeps walking past our scripted
                // responses, fail the test by emitting an empty terminator.
                ContactEmailsPageResponse(code = 1000)
            } else {
                queue.removeFirst()
            }
        }

        override suspend fun getContact(id: String): GetContactResponse =
            error("FakeContactsApi.getContact called unexpectedly in pager tests")

        override suspend fun listContacts(
            page: Int,
            pageSize: Int,
            labelIdFilter: String?
        ): ContactsPageResponse =
            error("FakeContactsApi.listContacts called unexpectedly in email pager tests")

        override suspend fun createContacts(request: CreateContactsRequest): CreateContactsResponse =
            error("not used in pager tests")

        override suspend fun updateContact(id: String, request: UpdateContactRequest): UpdateContactResponse =
            error("not used in pager tests")

        override suspend fun deleteContacts(request: BulkDeleteRequest): BulkDeleteResponse =
            error("not used in pager tests")
    }
}
