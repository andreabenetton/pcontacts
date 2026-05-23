// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.proton.api.http

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

/**
 * End-to-end: an authenticated GET hits 401 → the authenticator
 * fires /auth/refresh → the original request is replayed with the
 * fresh bearer → 200. Drives the real OkHttp client through
 * MockWebServer to prove the wiring.
 */
class RefreshingAuthenticatorEndToEndTest {

    private lateinit var server: MockWebServer
    private lateinit var session: InMemorySession
    private val persisted = mutableListOf<Pair<String, String>>()

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        session = InMemorySession(uid = "uid-1", accessToken = "stale-access")
        persisted.clear()
    }

    @After fun tearDown() { server.shutdown() }

    @Test fun authenticated_call_with_401_refreshes_then_retries_and_persists_new_tokens() = runTest {
        // 1) The original /users call returns 401 — triggers the authenticator.
        // 2) The authenticator calls /auth/refresh — returns 200 with fresh tokens.
        // 3) /users is retried with the new bearer — returns 200.
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"Code":401,"Error":"expired"}"""))
        server.enqueue(MockResponse().setBody(
            """
            {
              "AccessToken":"fresh-access",
              "RefreshToken":"fresh-refresh",
              "TokenType":"Bearer",
              "ExpiresIn":86400,
              "UID":"uid-1",
              "Code":1000
            }
            """.trimIndent()
        ))
        server.enqueue(MockResponse().setBody(
            """{"Code":1000,"User":{"ID":"user-1","Keys":[]}}"""
        ))

        val refreshConfig = ProtonApiFactory.RefreshConfig(
            mutableSession = session,
            getRefreshToken = { "stored-refresh" },
            onTokensRefreshed = { a, r -> persisted += a to r }
        )
        val apis = ProtonApiFactory(
            config = ProtonApiConfig(baseUrl = server.url("/").toString()),
            session = session,
            refreshConfig = refreshConfig
        )

        val response = apis.users.getUser()

        assertEquals("user-1", response.user.id)
        assertEquals("fresh-access", session.accessToken())
        assertEquals(listOf("fresh-access" to "fresh-refresh"), persisted)

        // Recorded request order: /users (401), /auth/refresh (200), /users (200).
        val first = server.takeRequest()
        assertEquals("/core/v4/users", first.path)
        assertEquals("Bearer stale-access", first.getHeader("Authorization"))

        val refresh = server.takeRequest()
        assertEquals("/auth/refresh", refresh.path)
        assertTrue(refresh.body.readUtf8().contains("\"RefreshToken\":\"stored-refresh\""))

        val retry = server.takeRequest()
        assertEquals("/core/v4/users", retry.path)
        assertEquals("Bearer fresh-access", retry.getHeader("Authorization"))
    }

    @Test fun second_401_in_a_row_gives_up_no_third_attempt() = runTest {
        // /users 401 → refresh 200 → /users 401 again → authenticator returns null,
        // OkHttp propagates the 401 to the caller. No third request lands.
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setBody(
            """
            {
              "AccessToken":"fresh-access",
              "RefreshToken":"fresh-refresh",
              "TokenType":"Bearer",
              "ExpiresIn":86400,
              "UID":"uid-1",
              "Code":1000
            }
            """.trimIndent()
        ))
        server.enqueue(MockResponse().setResponseCode(401))

        val refreshConfig = ProtonApiFactory.RefreshConfig(
            mutableSession = session,
            getRefreshToken = { "stored-refresh" },
            onTokensRefreshed = { _, _ -> }
        )
        val apis = ProtonApiFactory(
            config = ProtonApiConfig(baseUrl = server.url("/").toString()),
            session = session,
            refreshConfig = refreshConfig
        )

        val ex = try {
            apis.users.getUser()
            null
        } catch (t: Throwable) {
            t
        }
        // Retrofit throws HttpException for non-2xx; either it's that or any
        // other throwable — we just want a non-null failure surfaced.
        assertTrue("expected the 401 to propagate, got $ex", ex != null)

        // Exactly 3 requests on the wire (no infinite loop).
        assertEquals(3, server.requestCount)
    }
}
