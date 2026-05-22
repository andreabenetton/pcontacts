// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.proton.api.http

import io.pcontacts.core.proton.api.ProtonApiConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class HeadersInterceptorTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After fun tearDown() {
        server.shutdown()
    }

    @Test fun attaches_accept_and_appversion_headers() {
        server.enqueue(MockResponse().setBody("{}"))
        val client = OkHttpClient.Builder()
            .addInterceptor(HeadersInterceptor(ProtonApiConfig(baseUrl = server.url("/").toString(), appVersion = "android-contacts@0.1.0")))
            .build()

        client.newCall(Request.Builder().url(server.url("/x")).build()).execute().use { it.body?.close() }

        val recorded = server.takeRequest()
        assertEquals("application/vnd.protonmail.v1+json", recorded.getHeader("accept"))
        assertEquals("android-contacts@0.1.0", recorded.getHeader("x-pm-appversion"))
        assertNull("locale should not be attached when null", recorded.getHeader("x-pm-locale"))
    }

    @Test fun attaches_locale_when_configured() {
        server.enqueue(MockResponse().setBody("{}"))
        val client = OkHttpClient.Builder()
            .addInterceptor(HeadersInterceptor(ProtonApiConfig(baseUrl = server.url("/").toString(), locale = "en_US")))
            .build()

        client.newCall(Request.Builder().url(server.url("/x")).build()).execute().use { it.body?.close() }

        assertEquals("en_US", server.takeRequest().getHeader("x-pm-locale"))
    }

    @Test fun does_not_attach_uid_or_authorization() {
        server.enqueue(MockResponse().setBody("{}"))
        val client = OkHttpClient.Builder()
            .addInterceptor(HeadersInterceptor(ProtonApiConfig(baseUrl = server.url("/").toString())))
            .build()

        client.newCall(Request.Builder().url(server.url("/x")).build()).execute().use { it.body?.close() }

        val recorded = server.takeRequest()
        assertNull("HeadersInterceptor must not touch auth headers", recorded.getHeader("x-pm-uid"))
        assertNull("HeadersInterceptor must not touch auth headers", recorded.getHeader("Authorization"))
    }
}
