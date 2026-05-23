// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.proton.api.users

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

class ProtonUsersApiTest {

    private lateinit var server: MockWebServer
    private lateinit var session: InMemorySession
    private lateinit var api: ProtonUsersApi

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        session = InMemorySession()
        val cfg = ProtonApiConfig(baseUrl = server.url("/").toString())
        api = ProtonApiFactory(cfg, session).users
    }

    @After fun tearDown() {
        server.shutdown()
    }

    @Test fun getUser_parses_keys_and_carries_session_headers() = runTest {
        session.update(uid = "uid-x", accessToken = "access-x")
        server.enqueue(
            MockResponse().setBody(
                """
                {
                    "Code":1000,
                    "User":{
                        "ID":"user-1",
                        "Name":"alice",
                        "DisplayName":"Alice",
                        "Email":"alice@proton.me",
                        "Keys":[
                            {"ID":"key-1","Version":3,"Primary":1,"Active":1,
                             "PrivateKey":"-----BEGIN PGP PRIVATE KEY BLOCK-----...",
                             "Fingerprint":"deadbeef","Flags":3},
                            {"ID":"key-2","Version":3,"Primary":0,"Active":1,
                             "PrivateKey":"-----BEGIN PGP PRIVATE KEY BLOCK-----...",
                             "Fingerprint":"cafe","Flags":3}
                        ]
                    }
                }
                """.trimIndent()
            )
        )

        val response = api.getUser()

        assertEquals(1000, response.code)
        assertEquals("user-1", response.user.id)
        assertEquals(2, response.user.keys.size)
        val primary = response.user.keys.single { it.primary == 1 }
        assertEquals("key-1", primary.id)
        assertTrue(primary.privateKey.startsWith("-----BEGIN PGP PRIVATE KEY BLOCK-----"))

        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/core/v4/users", recorded.path)
        assertEquals("uid-x", recorded.getHeader("x-pm-uid"))
        assertEquals("Bearer access-x", recorded.getHeader("Authorization"))
    }

    @Test fun getKeySalts_parses_per_key_salts() = runTest {
        session.update(uid = "uid-x", accessToken = "access-x")
        server.enqueue(
            MockResponse().setBody(
                """
                {
                    "Code":1000,
                    "KeySalts":[
                        {"ID":"key-1","KeySalt":"c2FsdC1mb3Ita2V5LTE="},
                        {"ID":"key-2","KeySalt":"c2FsdC1mb3Ita2V5LTI="},
                        {"ID":"key-3","KeySalt":null}
                    ]
                }
                """.trimIndent()
            )
        )

        val response = api.getKeySalts()

        assertEquals(3, response.keySalts.size)
        assertEquals("c2FsdC1mb3Ita2V5LTE=", response.keySalts.first { it.keyId == "key-1" }.keySalt)
        // Forwarded / activation-pending keys can have null KeySalt — must
        // deserialize without crashing.
        assertEquals(null, response.keySalts.single { it.keyId == "key-3" }.keySalt)

        val recorded = server.takeRequest()
        assertEquals("/core/v4/keys/salts", recorded.path)
    }

    @Test fun getUser_tolerates_unknown_top_level_and_per_key_fields() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                    "Code":1000,
                    "User":{
                        "ID":"user-1",
                        "Keys":[{"ID":"key-1","PrivateKey":"...","FuturePremiumField":true}],
                        "ServerSideExperiment":"value"
                    },
                    "AnotherUnknown":42
                }
                """.trimIndent()
            )
        )

        val response = api.getUser()
        assertEquals("user-1", response.user.id)
        assertEquals(1, response.user.keys.size)
    }
}
