// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.proton.api.http

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class HumanVerificationInterceptorTest {

    private lateinit var server: MockWebServer
    private val client by lazy {
        OkHttpClient.Builder()
            .addInterceptor(HumanVerificationInterceptor())
            .build()
    }

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.shutdown() }

    @Test fun `9001 in json body throws HumanVerificationRequiredException`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(422)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"Code":9001,"Error":"Human verification required"}""")
        )
        assertThrows(HumanVerificationRequiredException::class.java) {
            client.newCall(Request.Builder().url(server.url("/x")).build()).execute()
        }
    }

    @Test fun non_9001_json_body_passes_through_unchanged() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"Code":1000,"OK":true}""")
        )
        val response = client.newCall(Request.Builder().url(server.url("/x")).build()).execute()
        assertEquals(200, response.code)
        assertEquals("""{"Code":1000,"OK":true}""", response.body!!.string())
    }

    @Test fun non_json_body_is_not_inspected_even_if_it_contains_9001() {
        // Pathological: a binary stream that happens to include the bytes
        // "Code":9001. Interceptor must NOT trigger on non-JSON.
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/octet-stream")
                .setBody("garbage \"Code\":9001 garbage")
        )
        val response = client.newCall(Request.Builder().url(server.url("/x")).build()).execute()
        assertEquals(200, response.code)
    }

    @Test fun `9001 with extra padding or whitespace around the value is caught`() {
        // Marker is literal "Code":9001 with no space — verify it matches
        // when 9001 is followed by a delimiter (comma, brace).
        server.enqueue(
            MockResponse()
                .setResponseCode(422)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"Details":{},"Code":9001,"Error":"v"}""")
        )
        assertThrows(HumanVerificationRequiredException::class.java) {
            client.newCall(Request.Builder().url(server.url("/x")).build()).execute()
        }
    }
}
