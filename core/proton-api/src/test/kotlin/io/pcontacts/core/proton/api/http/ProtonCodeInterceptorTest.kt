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

class ProtonCodeInterceptorTest {

    private lateinit var server: MockWebServer
    private val client by lazy { OkHttpClient.Builder().addInterceptor(ProtonCodeInterceptor()).build() }

    @Before fun setUp() { server = MockWebServer().apply { start() } }

    @After fun tearDown() { server.shutdown() }

    private fun get() = client.newCall(Request.Builder().url(server.url("/x")).build()).execute()

    private fun enqueue(status: Int, body: String, type: String = "application/json") {
        server.enqueue(MockResponse().setResponseCode(status).setHeader("Content-Type", type).setBody(body))
    }

    @Test fun `code 1000 and the 1001 multi-status envelope pass through`() {
        enqueue(200, """{"Code":1000,"Contact":{}}""")
        assertEquals(200, get().code)
        enqueue(200, """{"Code":1001,"Responses":[{"Index":0,"Response":{"Code":2001}}]}""")
        assertEquals(200, get().code)
    }

    @Test fun `a non-success code inside a 2xx throws ProtonApiException`() {
        enqueue(200, """{"Code":2001,"Error":"Invalid input"}""")

        val ex = assertThrows(ProtonApiException::class.java) { get() }

        assertEquals(2001, ex.protonCode)
        assertEquals("Invalid input", ex.error)
        assertEquals("Proton Code:2001", ex.message)
    }

    @Test fun `http errors and bodies without a code are left to the caller`() {
        enqueue(422, """{"Code":2002,"Error":"UID Field is missing"}""")
        assertEquals(422, get().code)
        enqueue(200, """{"Contacts":[]}""")
        assertEquals(200, get().code)
        enqueue(200, "<html/>", type = "text/html")
        assertEquals(200, get().code)
    }
}
