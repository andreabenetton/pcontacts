// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.proton.api.contacts

import io.pcontacts.core.proton.api.InMemorySession
import io.pcontacts.core.proton.api.ProtonApiConfig
import io.pcontacts.core.proton.api.retrofit.ProtonApiFactory
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProtonContactsApiTest {

    private lateinit var server: MockWebServer
    private lateinit var session: InMemorySession
    private lateinit var api: ProtonContactsApi

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        session = InMemorySession()
        val cfg = ProtonApiConfig(baseUrl = server.url("/").toString())
        api = ProtonApiFactory(cfg, session).contacts
    }

    @After fun tearDown() {
        server.shutdown()
    }

    @Test fun listContactEmails_serializes_query_and_parses_response() = runTest {
        session.update(uid = "uid-x", accessToken = "access-x")
        server.enqueue(
            MockResponse().setBody(
                """
                {
                    "Code":1000,
                    "Total":2,
                    "ContactEmails":[
                        {"ID":"e1","Email":"alice@proton.me","Name":"Alice",
                         "Type":["home"],"Defaults":1,"Order":0,
                         "ContactID":"c1","LabelIDs":["l1"],"LastUsedTime":17000},
                        {"ID":"e2","Email":"bob@proton.me","Name":"Bob",
                         "Type":[],"Defaults":0,"Order":1,
                         "ContactID":"c2","LabelIDs":[],"LastUsedTime":17001}
                    ]
                }
                """.trimIndent()
            )
        )

        val response = api.listContactEmails(page = 0, pageSize = 1000)

        assertEquals(2, response.contactEmails.size)
        assertEquals("alice@proton.me", response.contactEmails[0].email)
        assertEquals("c1", response.contactEmails[0].contactId)
        assertEquals(listOf("home"), response.contactEmails[0].type)
        assertEquals(listOf("l1"), response.contactEmails[0].labelIds)

        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        // Page and PageSize are required; filter params absent when caller passed null.
        assertEquals("/contacts/v4/contacts/emails?Page=0&PageSize=1000", recorded.path)
        // Authenticated request — session headers must travel.
        assertEquals("uid-x", recorded.getHeader("x-pm-uid"))
        assertEquals("Bearer access-x", recorded.getHeader("Authorization"))
    }

    @Test fun listContactEmails_passes_email_filter_as_query_param() = runTest {
        server.enqueue(
            MockResponse().setBody("""{"Code":1000,"Total":0,"ContactEmails":[]}""")
        )

        api.listContactEmails(page = 0, pageSize = 500, emailFilter = "alice@proton.me")

        val recorded = server.takeRequest()
        // OkHttp percent-encodes the @ sign in query strings.
        assertTrue("missing Email param: ${recorded.path}",
            recorded.path!!.contains("Email=alice%40proton.me"))
    }

    @Test fun listContactEmails_ignores_unknown_server_fields() = runTest {
        // Server adds a field we don't model; deserialization must not blow up.
        server.enqueue(
            MockResponse().setBody(
                """
                {
                    "Code":1000,
                    "Total":1,
                    "ContactEmails":[
                        {"ID":"e1","Email":"alice@proton.me","ContactID":"c1",
                         "Type":[],"LabelIDs":[],
                         "BrandNewServerField":"surprise"}
                    ],
                    "AnotherUnknownField":42
                }
                """.trimIndent()
            )
        )

        val response = api.listContactEmails(page = 0, pageSize = 1000)
        assertEquals(1, response.contactEmails.size)
        assertEquals("alice@proton.me", response.contactEmails[0].email)
    }
}
