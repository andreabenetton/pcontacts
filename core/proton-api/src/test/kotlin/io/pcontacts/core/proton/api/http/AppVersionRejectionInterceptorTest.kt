// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.proton.api.http

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AppVersionRejectionInterceptorTest {

    private lateinit var server: MockWebServer
    private val client by lazy {
        OkHttpClient.Builder()
            .addInterceptor(AppVersionRejectionInterceptor())
            .build()
    }

    @Before fun setUp() { server = MockWebServer().apply { start() } }

    @After fun tearDown() { server.shutdown() }

    private fun get() = client.newCall(Request.Builder().url(server.url("/x")).build()).execute()

    @Test fun `code 5003 throws AppVersionRejectedException`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(422)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"Code":5003,"Error":"Bad app version"}""")
        )
        val ex = assertThrows(AppVersionRejectedException::class.java) { get() }
        assertEquals(5003, ex.protonCode)
    }

    @Test fun `code 5004 throws AppVersionRejectedException`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"Code":5004,"Error":"API version not supported"}""")
        )
        val ex = assertThrows(AppVersionRejectedException::class.java) { get() }
        assertEquals(5004, ex.protonCode)
    }

    @Test fun `code 1000 passes through unchanged`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"Code":1000,"OK":true}""")
        )
        val response = get()
        assertEquals(200, response.code)
        assertEquals("""{"Code":1000,"OK":true}""", response.body.string())
    }

    @Test fun `code 9001 is not caught by this interceptor`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(422)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"Code":9001,"Error":"Human verification required"}""")
        )
        val response = get()
        assertEquals(422, response.code)
    }

    @Test fun `non_json body is not inspected`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/octet-stream")
                .setBody("""{"Code":5003}""")
        )
        val response = get()
        assertEquals(200, response.code)
    }

    @Test fun `5003 with Code field not first is caught`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(422)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"Error":"outdated","Details":{},"Code":5003}""")
        )
        assertThrows(AppVersionRejectedException::class.java) { get() }
    }

    @Test fun `5003 with whitespace and pretty printing is caught`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(422)
                .setHeader("Content-Type", "application/json")
                .setBody("{\n  \"Error\": \"Bad app version\",\n  \"Code\": 5003\n}")
        )
        assertThrows(AppVersionRejectedException::class.java) { get() }
    }

    @Test fun `http 401 with version code is caught`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"Code":5003,"Error":"Force upgrade"}""")
        )
        assertThrows(AppVersionRejectedException::class.java) { get() }
    }

    // ---- Unit tests for extractCode directly ----

    @Test fun extractCode_returns_int_for_valid_json() {
        assertEquals(5003, AppVersionRejectionInterceptor.extractCode("""{"Code":5003}"""))
    }

    @Test fun extractCode_returns_null_for_no_code_field() {
        assertNull(AppVersionRejectionInterceptor.extractCode("""{"Error":"x"}"""))
    }

    @Test fun extractCode_returns_null_for_invalid_json() {
        assertNull(AppVersionRejectionInterceptor.extractCode("not json"))
    }

    @Test fun extractCode_handles_string_code() {
        assertEquals(5003, AppVersionRejectionInterceptor.extractCode("""{"Code":"5003"}"""))
    }

    @Test fun extractCode_returns_null_for_non_numeric_string() {
        assertNull(AppVersionRejectionInterceptor.extractCode("""{"Code":"abc"}"""))
    }

    @Test fun `VERSION_REJECTION_CODES contains 5003 and 5004`() {
        assertTrue(5003 in AppVersionRejectionInterceptor.VERSION_REJECTION_CODES)
        assertTrue(5004 in AppVersionRejectionInterceptor.VERSION_REJECTION_CODES)
        assertEquals(2, AppVersionRejectionInterceptor.VERSION_REJECTION_CODES.size)
    }
}
