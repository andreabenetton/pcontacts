// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.proton.api.http

import io.pcontacts.core.logging.Logger
import io.pcontacts.core.logging.NoOpSink
import io.pcontacts.core.logging.RedactingLogger
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Per plan §3.6 and ADR-0015 / risk-#8: when Proton rate-limits a
 * call (HTTP 429), retry with Fibonacci backoff. Honours the
 * `Retry-After` header when present (overrides the backoff
 * schedule).
 *
 * Mirrors the web client's `refreshHandlers.ts` approach. Cap at
 * `maxRetries` so a stuck account doesn't pin a background thread
 * forever — after the cap the 429 propagates to the caller, which
 * surfaces a sync error and stops thrashing.
 *
 * `sleeper` is injected so tests can verify the schedule without
 * real Thread.sleep.
 */
class FibonacciBackoffInterceptor(
    private val maxRetries: Int = DEFAULT_MAX_RETRIES,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
    private val logger: Logger = RedactingLogger(tag = "Backoff", sink = NoOpSink)
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.method == "POST") return chain.proceed(request)

        var attempt = 0
        var response = chain.proceed(request)

        while (response.code == HTTP_TOO_MANY_REQUESTS && attempt < maxRetries) {
            val retryAfterMs = response.header(RETRY_AFTER)?.toLongOrNull()?.times(1000)
            val sleepMs = retryAfterMs ?: fibonacciMillis(attempt)
            response.close()
            logger.warn { "429 on ${request.method} ${request.url.encodedPath}; backing off ${sleepMs}ms (attempt=${attempt + 1}/$maxRetries)" }
            sleeper(sleepMs)
            attempt += 1
            response = chain.proceed(request)
        }

        if (response.code == HTTP_TOO_MANY_REQUESTS) {
            logger.warn { "429 persisted past $maxRetries retries; surfacing to caller" }
        }
        return response
    }

    /**
     * 1s, 2s, 3s, 5s, 8s, 13s, ... — the Fibonacci sequence in
     * milliseconds. n=0 returns 1000ms; each subsequent step adds
     * the previous two.
     */
    internal fun fibonacciMillis(n: Int): Long {
        require(n >= 0) { "n must be non-negative" }
        var a = 1L
        var b = 2L
        repeat(n) {
            val next = a + b
            a = b
            b = next
        }
        return a * 1000L
    }

    companion object {
        const val DEFAULT_MAX_RETRIES = 5
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val RETRY_AFTER = "Retry-After"
    }
}
