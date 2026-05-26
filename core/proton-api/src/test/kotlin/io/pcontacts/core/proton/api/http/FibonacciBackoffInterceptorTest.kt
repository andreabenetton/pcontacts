// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.proton.api.http

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class FibonacciBackoffInterceptorTest {

    private lateinit var server: MockWebServer
    private val sleeps = mutableListOf<Long>()
    private val client by lazy {
        OkHttpClient.Builder()
            .addInterceptor(
                FibonacciBackoffInterceptor(
                    maxRetries = 5,
                    sleeper = { ms -> sleeps += ms }
                )
            )
            .build()
    }

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        sleeps.clear()
    }

    @After fun tearDown() { server.shutdown() }

    @Test fun fibonacciMillis_returns_the_expected_sequence() {
        val interceptor = FibonacciBackoffInterceptor(maxRetries = 5, sleeper = {})
        assertEquals(1_000L, interceptor.fibonacciMillis(0))
        assertEquals(2_000L, interceptor.fibonacciMillis(1))
        assertEquals(3_000L, interceptor.fibonacciMillis(2))
        assertEquals(5_000L, interceptor.fibonacciMillis(3))
        assertEquals(8_000L, interceptor.fibonacciMillis(4))
        assertEquals(13_000L, interceptor.fibonacciMillis(5))
    }

    @Test fun single_429_then_200_sleeps_once_and_returns_200() {
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        val response = client.newCall(Request.Builder().url(server.url("/x")).build()).execute()

        assertEquals(200, response.code)
        assertEquals(listOf(1_000L), sleeps)
        assertEquals(2, server.requestCount)
    }

    @Test fun successive_429s_back_off_through_the_fibonacci_sequence() {
        repeat(4) { server.enqueue(MockResponse().setResponseCode(429)) }
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        val response = client.newCall(Request.Builder().url(server.url("/x")).build()).execute()

        assertEquals(200, response.code)
        // Attempts 0..3 produced 4 sleeps: 1s, 2s, 3s, 5s.
        assertEquals(listOf(1_000L, 2_000L, 3_000L, 5_000L), sleeps)
        assertEquals(5, server.requestCount)
    }

    @Test fun retry_after_header_overrides_the_fibonacci_schedule() {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "42"))
        server.enqueue(MockResponse().setResponseCode(200))

        client.newCall(Request.Builder().url(server.url("/x")).build()).execute().close()

        // 42 seconds → 42_000 ms, NOT 1_000.
        assertEquals(listOf(42_000L), sleeps)
    }

    @Test fun post_request_is_not_retried_on_429() {
        server.enqueue(MockResponse().setResponseCode(429))

        val request = Request.Builder()
            .url(server.url("/contacts/v4/contacts"))
            .post("{}".toRequestBody(null))
            .build()
        val response = client.newCall(request).execute()

        assertEquals(429, response.code)
        assertEquals(0, sleeps.size)
        assertEquals(1, server.requestCount)
    }

    @Test fun persistent_429_propagates_after_maxRetries() {
        repeat(6) { server.enqueue(MockResponse().setResponseCode(429)) }

        val response = client.newCall(Request.Builder().url(server.url("/x")).build()).execute()

        assertEquals(429, response.code)
        // 5 retries × backoff sleeps.
        assertEquals(5, sleeps.size)
        // Original request + 5 retries = 6 total wire requests.
        assertEquals(6, server.requestCount)
    }
}
