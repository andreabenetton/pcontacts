// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.proton.api.auth

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

class ProtonAuthApiTest {

    private lateinit var server: MockWebServer
    private lateinit var session: InMemorySession
    private lateinit var api: ProtonAuthApi

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        session = InMemorySession()
        val cfg = ProtonApiConfig(baseUrl = server.url("/").toString())
        api = ProtonApiFactory(cfg, session).auth
    }

    @After fun tearDown() {
        server.shutdown()
    }

    @Test fun getInfo_serializes_request_and_parses_response() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                    "Modulus":"-----BEGIN PGP SIGNED MESSAGE----- ... -----END PGP SIGNATURE-----",
                    "ServerEphemeral":"server-ephemeral-b64",
                    "Version":4,
                    "Salt":"salt-b64",
                    "SRPSession":"srp-session-id",
                    "Code":1000
                }
                """.trimIndent()
            )
        )

        val response = api.getInfo(InfoRequest(username = "alice@proton.me"))

        assertEquals(4, response.version)
        assertEquals("srp-session-id", response.srpSession)
        assertEquals("salt-b64", response.salt)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/core/v4/auth/info", recorded.path)
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"Username\":\"alice@proton.me\""))
        assertTrue(body.contains("\"Intent\":\"Proton\""))
    }

    @Test fun auth_round_trip_returns_tokens_and_serverProof() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                    "AccessToken":"access-b64",
                    "RefreshToken":"refresh-b64",
                    "TokenType":"Bearer",
                    "ExpiresIn":86400,
                    "UID":"uid-b64",
                    "UserID":"user-id",
                    "PasswordMode":1,
                    "TwoFactor":0,
                    "ServerProof":"server-proof-b64",
                    "Code":1000
                }
                """.trimIndent()
            )
        )

        val response = api.auth(
            AuthRequest(
                username = "alice@proton.me",
                clientEphemeral = "client-ephemeral",
                clientProof = "client-proof",
                srpSession = "srp-session-id"
            )
        )

        assertEquals("access-b64", response.accessToken)
        assertEquals("refresh-b64", response.refreshToken)
        assertEquals("uid-b64", response.uid)
        assertEquals("server-proof-b64", response.serverProof)
        assertEquals(0, response.twoFactor)

        val recorded = server.takeRequest()
        assertEquals("/core/v4/auth", recorded.path)
    }

    @Test fun authenticated_call_carries_session_headers() = runTest {
        session.update(uid = "live-uid", accessToken = "live-access")
        server.enqueue(MockResponse().setResponseCode(200))

        api.revoke()

        val recorded = server.takeRequest()
        assertEquals("DELETE", recorded.method)
        assertEquals("/core/v4/auth", recorded.path)
        assertEquals("live-uid", recorded.getHeader("x-pm-uid"))
        assertEquals("Bearer live-access", recorded.getHeader("Authorization"))
        assertEquals(
            "application/vnd.protonmail.v1+json",
            recorded.getHeader("accept")
        )
    }
}
