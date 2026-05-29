// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.proton.api.addresses

import io.pcontacts.core.proton.api.InMemorySession
import io.pcontacts.core.proton.api.ProtonApiConfig
import io.pcontacts.core.proton.api.retrofit.ProtonApiFactory
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProtonAddressesApiTest {

    private lateinit var server: MockWebServer
    private lateinit var session: InMemorySession
    private lateinit var api: ProtonAddressesApi

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        session = InMemorySession()
        val cfg = ProtonApiConfig(baseUrl = server.url("/").toString())
        api = ProtonApiFactory(cfg, session).addresses
    }

    @After fun tearDown() {
        server.shutdown()
    }

    @Test fun getAddresses_parses_one_address_with_two_keys_and_carries_session_headers() = runTest {
        session.update(uid = "uid-x", accessToken = "access-x")
        server.enqueue(
            MockResponse().setBody(
                """
                {
                    "Code":1000,
                    "Addresses":[
                        {
                            "ID":"addr-1",
                            "Email":"alice@proton.me",
                            "Status":1,
                            "Receive":1,
                            "Send":1,
                            "Keys":[
                                {"ID":"akey-1","AddressID":"addr-1","Primary":1,"Active":1,"Flags":3,
                                 "PrivateKey":"-----BEGIN PGP PRIVATE KEY BLOCK-----...",
                                 "Token":"-----BEGIN PGP MESSAGE-----...",
                                 "Signature":"-----BEGIN PGP SIGNATURE-----...",
                                 "Fingerprint":"deadbeef"},
                                {"ID":"akey-2","AddressID":"addr-1","Primary":0,"Active":1,"Flags":3,
                                 "PrivateKey":"-----BEGIN PGP PRIVATE KEY BLOCK-----...",
                                 "Token":"-----BEGIN PGP MESSAGE-----...",
                                 "Signature":"-----BEGIN PGP SIGNATURE-----...",
                                 "Fingerprint":"cafe"}
                            ]
                        }
                    ]
                }
                """.trimIndent()
            )
        )

        val response = api.getAddresses()

        assertEquals(1000, response.code)
        assertEquals(1, response.addresses.size)
        val address = response.addresses.single()
        assertEquals("addr-1", address.id)
        assertEquals("alice@proton.me", address.email)
        assertEquals(2, address.keys.size)
        val primary = address.keys.single { it.primary == 1 }
        assertEquals("akey-1", primary.id)
        assertTrue(primary.privateKey.startsWith("-----BEGIN PGP PRIVATE KEY BLOCK-----"))
        assertTrue(primary.token!!.startsWith("-----BEGIN PGP MESSAGE-----"))

        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/core/v4/addresses", recorded.path)
        assertEquals("uid-x", recorded.getHeader("x-pm-uid"))
        assertEquals("Bearer access-x", recorded.getHeader("Authorization"))
    }

    @Test fun getAddresses_tolerates_v1_legacy_keys_with_null_Token_and_Signature() = runTest {
        // [V] Pre-key-transparency address keys (WebClients
        // getDecryptedAddressKeys.ts:hasMigratedKeys=false branch) omit
        // Token and Signature; the decrypt path unlocks those keys with
        // the user keyPassword directly. Must deserialize cleanly.
        server.enqueue(
            MockResponse().setBody(
                """
                {
                    "Code":1000,
                    "Addresses":[
                        {
                            "ID":"addr-legacy",
                            "Email":"legacy@proton.me",
                            "Keys":[
                                {"ID":"akey-legacy","PrivateKey":"-----BEGIN PGP PRIVATE KEY BLOCK-----..."}
                            ]
                        }
                    ]
                }
                """.trimIndent()
            )
        )

        val response = api.getAddresses()

        val key = response.addresses.single().keys.single()
        assertEquals("akey-legacy", key.id)
        assertNull(key.token)
        assertNull(key.signature)
        // Defaults must populate for fields the server omitted.
        assertEquals(1, key.active)
        assertEquals(0, key.primary)
    }

    @Test fun getAddresses_tolerates_unknown_top_level_and_per_key_fields() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                    "Code":1000,
                    "Addresses":[
                        {
                            "ID":"addr-1",
                            "Email":"alice@proton.me",
                            "Keys":[{"ID":"akey-1","PrivateKey":"...","FuturePremiumField":true}],
                            "ServerSideExperiment":"value"
                        }
                    ],
                    "AnotherUnknown":42
                }
                """.trimIndent()
            )
        )

        val response = api.getAddresses()
        assertEquals("addr-1", response.addresses.single().id)
        assertEquals(1, response.addresses.single().keys.size)
    }
}
