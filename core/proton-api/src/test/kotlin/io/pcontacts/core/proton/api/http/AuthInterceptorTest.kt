// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.proton.api.http

import io.pcontacts.core.proton.api.InMemorySession
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class AuthInterceptorTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After fun tearDown() {
        server.shutdown()
    }

    @Test fun no_auth_headers_when_session_empty() {
        server.enqueue(MockResponse().setBody("{}"))
        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(InMemorySession()))
            .build()

        client.newCall(Request.Builder().url(server.url("/x")).build()).execute().use { it.body.close() }

        val recorded = server.takeRequest()
        assertNull(recorded.getHeader("x-pm-uid"))
        assertNull(recorded.getHeader("Authorization"))
    }

    @Test fun attaches_uid_and_bearer_when_session_present() {
        server.enqueue(MockResponse().setBody("{}"))
        val session = InMemorySession(uid = "session-uid-abc", accessToken = "access-token-xyz")
        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(session))
            .build()

        client.newCall(Request.Builder().url(server.url("/x")).build()).execute().use { it.body.close() }

        val recorded = server.takeRequest()
        assertEquals("session-uid-abc", recorded.getHeader("x-pm-uid"))
        assertEquals("Bearer access-token-xyz", recorded.getHeader("Authorization"))
    }

    @Test fun partial_session_does_not_emit_partial_headers() {
        // uid without accessToken — must not send either header.
        server.enqueue(MockResponse().setBody("{}"))
        val session = InMemorySession(uid = "only-uid", accessToken = null)
        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(session))
            .build()

        client.newCall(Request.Builder().url(server.url("/x")).build()).execute().use { it.body.close() }

        val recorded = server.takeRequest()
        assertNull(recorded.getHeader("x-pm-uid"))
        assertNull(recorded.getHeader("Authorization"))
    }
}
