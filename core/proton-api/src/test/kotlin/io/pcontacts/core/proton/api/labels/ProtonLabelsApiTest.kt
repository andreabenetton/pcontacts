// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.proton.api.labels

import io.pcontacts.core.proton.api.InMemorySession
import io.pcontacts.core.proton.api.ProtonApiConfig
import io.pcontacts.core.proton.api.retrofit.ProtonApiFactory
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class ProtonLabelsApiTest {

    private lateinit var server: MockWebServer
    private lateinit var session: InMemorySession
    private lateinit var api: ProtonLabelsApi

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        session = InMemorySession()
        val cfg = ProtonApiConfig(baseUrl = server.url("/").toString())
        api = ProtonApiFactory(cfg, session).labels
    }

    @After fun tearDown() { server.shutdown() }

    @Test fun listLabels_with_contact_group_type_parses_array() = runTest {
        session.update(uid = "uid-x", accessToken = "access-x")
        server.enqueue(
            MockResponse().setBody(
                """
                {
                    "Code":1000,
                    "Labels":[
                        {"ID":"label-1","Name":"Family","Type":2,"Path":"Family"},
                        {"ID":"label-2","Name":"Work","Type":2,"Path":"Work","ParentID":null}
                    ]
                }
                """.trimIndent()
            )
        )

        val response = api.listLabels(type = LabelType.CONTACT_GROUP)

        assertEquals(2, response.labels.size)
        assertEquals("Family", response.labels[0].name)
        assertEquals(LabelType.CONTACT_GROUP, response.labels[0].type)

        val recorded = server.takeRequest()
        assertEquals("/core/v4/labels?Type=2", recorded.path)
        assertEquals("uid-x", recorded.getHeader("x-pm-uid"))
    }

    @Test fun unknown_label_fields_are_tolerated() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                    "Code":1000,
                    "Labels":[{"ID":"l1","Name":"X","Type":2,"Order":3,"Color":"#abcdef","Sticky":1}]
                }
                """.trimIndent()
            )
        )
        val response = api.listLabels(type = LabelType.CONTACT_GROUP)
        assertEquals(1, response.labels.size)
        assertEquals("X", response.labels[0].name)
    }
}
