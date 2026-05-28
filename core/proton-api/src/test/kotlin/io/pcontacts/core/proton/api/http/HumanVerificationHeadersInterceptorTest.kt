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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HumanVerificationHeadersInterceptorTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After fun tearDown() {
        server.shutdown()
    }

    private class MutableTokens(
        @Volatile var t: String? = null,
        @Volatile var ty: String? = null
    ) : HumanVerificationTokenSource {
        @Volatile var cleared = false
            private set
        override fun token(): String? = t
        override fun tokenType(): String? = ty
        override fun clear() {
            t = null; ty = null; cleared = true
        }
    }

    private fun get(tokens: HumanVerificationTokenSource): okhttp3.Response =
        OkHttpClient.Builder()
            .addInterceptor(HumanVerificationHeadersInterceptor(tokens))
            .build()
            .newCall(Request.Builder().url(server.url("/x").toString()).build())
            .execute()

    @Test fun attaches_both_headers_when_token_and_type_set() {
        server.enqueue(MockResponse().setBody("ok"))
        val tokens = MutableTokens(t = "abc123", ty = "captcha")

        get(tokens).close()

        val recorded = server.takeRequest()
        assertEquals("abc123", recorded.getHeader("x-pm-human-verification-token"))
        assertEquals("captcha", recorded.getHeader("x-pm-human-verification-token-type"))
    }

    @Test fun attaches_no_headers_when_token_is_null() {
        server.enqueue(MockResponse().setBody("ok"))
        val tokens = MutableTokens(t = null, ty = "captcha")

        get(tokens).close()

        val recorded = server.takeRequest()
        assertNull(recorded.getHeader("x-pm-human-verification-token"))
        assertNull(recorded.getHeader("x-pm-human-verification-token-type"))
    }

    @Test fun attaches_no_headers_when_type_is_null() {
        server.enqueue(MockResponse().setBody("ok"))
        val tokens = MutableTokens(t = "abc123", ty = null)

        get(tokens).close()

        val recorded = server.takeRequest()
        assertNull(recorded.getHeader("x-pm-human-verification-token"))
        assertNull(recorded.getHeader("x-pm-human-verification-token-type"))
    }

    @Test fun stale_token_9001_clears_store_via_paired_interceptor() {
        server.enqueue(
            MockResponse()
                .setResponseCode(422)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"Code":9001,"Error":"Human verification required"}""")
        )
        val tokens = MutableTokens(t = "stale-token", ty = "captcha")
        val client = OkHttpClient.Builder()
            .addInterceptor(HumanVerificationHeadersInterceptor(tokens))
            .addInterceptor(HumanVerificationInterceptor(tokens = tokens))
            .build()

        val thrown = runCatching {
            client.newCall(Request.Builder().url(server.url("/x").toString()).build()).execute()
        }.exceptionOrNull()

        assertTrue(
            "expected HumanVerificationRequiredException, was $thrown",
            thrown is HumanVerificationRequiredException
        )
        // The stale-token branch fired — the source was cleared.
        assertTrue("stale-token clear must have been called", tokens.cleared)
        assertNull(tokens.token())
        assertNull(tokens.tokenType())
    }

    @Test fun stale_token_12087_clears_store_via_paired_interceptor() {
        server.enqueue(
            MockResponse()
                .setResponseCode(422)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"Code":12087,"Error":"CAPTCHA validation failed","Details":{}}""")
        )
        val tokens = MutableTokens(t = "stale-token", ty = "captcha")
        val client = OkHttpClient.Builder()
            .addInterceptor(HumanVerificationHeadersInterceptor(tokens))
            .addInterceptor(HumanVerificationInterceptor(tokens = tokens))
            .build()

        val thrown = runCatching {
            client.newCall(Request.Builder().url(server.url("/x").toString()).build()).execute()
        }.exceptionOrNull()

        assertTrue(
            "expected HumanVerificationRequiredException, was $thrown",
            thrown is HumanVerificationRequiredException
        )
        assertNull((thrown as HumanVerificationRequiredException).verificationUrl)
        assertTrue("12087 must trigger token clear", tokens.cleared)
        assertNull(tokens.token())
        assertNull(tokens.tokenType())
    }
}
