// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.proton.api.contacts

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

class ProtonContactsApiTest {

    private lateinit var server: MockWebServer
    private lateinit var session: InMemorySession
    private lateinit var api: ProtonContactsApi

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        session = InMemorySession()
        val cfg = ProtonApiConfig(baseUrl = server.url("/").toString())
        api = ProtonApiFactory(cfg, session).contacts
    }

    @After fun tearDown() {
        server.shutdown()
    }

    @Test fun listContactEmails_serializes_query_and_parses_response() = runTest {
        session.update(uid = "uid-x", accessToken = "access-x")
        server.enqueue(
            MockResponse().setBody(
                """
                {
                    "Code":1000,
                    "Total":2,
                    "ContactEmails":[
                        {"ID":"e1","Email":"alice@proton.me","Name":"Alice",
                         "Type":["home"],"Defaults":1,"Order":0,
                         "ContactID":"c1","LabelIDs":["l1"],"LastUsedTime":17000},
                        {"ID":"e2","Email":"bob@proton.me","Name":"Bob",
                         "Type":[],"Defaults":0,"Order":1,
                         "ContactID":"c2","LabelIDs":[],"LastUsedTime":17001}
                    ]
                }
                """.trimIndent()
            )
        )

        val response = api.listContactEmails(page = 0, pageSize = 1000)

        assertEquals(2, response.contactEmails.size)
        assertEquals("alice@proton.me", response.contactEmails[0].email)
        assertEquals("c1", response.contactEmails[0].contactId)
        assertEquals(listOf("home"), response.contactEmails[0].type)
        assertEquals(listOf("l1"), response.contactEmails[0].labelIds)

        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        // Page and PageSize are required; filter params absent when caller passed null.
        assertEquals("/contacts/v4/contacts/emails?Page=0&PageSize=1000", recorded.path)
        // Authenticated request — session headers must travel.
        assertEquals("uid-x", recorded.getHeader("x-pm-uid"))
        assertEquals("Bearer access-x", recorded.getHeader("Authorization"))
    }

    @Test fun listContactEmails_passes_email_filter_as_query_param() = runTest {
        server.enqueue(
            MockResponse().setBody("""{"Code":1000,"Total":0,"ContactEmails":[]}""")
        )

        api.listContactEmails(page = 0, pageSize = 500, emailFilter = "alice@proton.me")

        val recorded = server.takeRequest()
        // OkHttp percent-encodes the @ sign in query strings.
        assertTrue("missing Email param: ${recorded.path}",
            recorded.path!!.contains("Email=alice%40proton.me"))
    }

    @Test fun getContact_parses_full_payload_including_Cards_and_signature() = runTest {
        session.update(uid = "uid-x", accessToken = "access-x")
        server.enqueue(
            MockResponse().setBody(
                """
                {
                    "Code":1000,
                    "Contact":{
                        "ID":"c1",
                        "Name":"Alice",
                        "UID":"vcard-uid-abc",
                        "Size":512,
                        "CreateTime":1700000000,
                        "ModifyTime":1700000100,
                        "Cards":[
                            {"Type":0,"Data":"BEGIN:VCARD\nVERSION:4.0\nEND:VCARD","Signature":null},
                            {"Type":2,"Data":"FN:Alice","Signature":"-----BEGIN PGP SIGNATURE-----..."},
                            {"Type":3,"Data":"-----BEGIN PGP MESSAGE-----...",
                             "Signature":"-----BEGIN PGP SIGNATURE-----..."}
                        ],
                        "ContactEmails":[],
                        "LabelIDs":[]
                    }
                }
                """.trimIndent()
            )
        )

        val response = api.getContact("c1")

        assertEquals(1000, response.code)
        assertEquals("c1", response.contact.id)
        assertEquals(1_700_000_100L, response.contact.modifyTime)
        assertEquals(3, response.contact.cards.size)

        val clear = response.contact.cards[0]
        assertEquals(0, clear.type)
        assertEquals(null, clear.signature)

        val signed = response.contact.cards[1]
        assertEquals(2, signed.type)
        assertTrue("signed card must carry a signature", !signed.signature.isNullOrBlank())

        val encryptedSigned = response.contact.cards[2]
        assertEquals(3, encryptedSigned.type)
        assertTrue(encryptedSigned.data.startsWith("-----BEGIN PGP MESSAGE-----"))

        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/contacts/v4/contacts/c1", recorded.path)
        assertEquals("uid-x", recorded.getHeader("x-pm-uid"))
        assertEquals("Bearer access-x", recorded.getHeader("Authorization"))
    }

    @Test fun listContacts_returns_metadata_array_with_modifyTime() = runTest {
        session.update(uid = "uid-x", accessToken = "access-x")
        server.enqueue(
            MockResponse().setBody(
                """
                {
                    "Code":1000,
                    "Total":2,
                    "Contacts":[
                        {"ID":"c1","Name":"Alice","UID":"vcard-uid-a",
                         "Size":256,"CreateTime":1700000000,"ModifyTime":1700000100,
                         "LabelIDs":["l1"]},
                        {"ID":"c2","Name":"Bob","UID":"vcard-uid-b",
                         "Size":300,"CreateTime":1700000050,"ModifyTime":1700000200,
                         "LabelIDs":[]}
                    ]
                }
                """.trimIndent()
            )
        )

        val response = api.listContacts(page = 0, pageSize = 1000)

        assertEquals(2, response.contacts.size)
        assertEquals("c1", response.contacts[0].id)
        assertEquals(1_700_000_100L, response.contacts[0].modifyTime)
        assertEquals(1_700_000_200L, response.contacts[1].modifyTime)

        val recorded = server.takeRequest()
        assertEquals("/contacts/v4/contacts?Page=0&PageSize=1000", recorded.path)
        assertEquals("uid-x", recorded.getHeader("x-pm-uid"))
    }

    // --- Write endpoint tests (ADR-0017 / ADR-0018, phase 9) ---

    @Test fun createContacts_serializes_body_and_parses_response() = runTest {
        session.update(uid = "uid-x", accessToken = "access-x")
        server.enqueue(
            MockResponse().setBody(
                """
                {
                    "Code":1000,
                    "Responses":[
                        {"Index":0,"Response":{"Code":1000,
                            "Contact":{"ID":"new-c1","Name":"","UID":"","Size":0,
                                       "CreateTime":1700000000,"ModifyTime":1700000000,
                                       "Cards":[],"ContactEmails":[],"LabelIDs":[]}}}
                    ]
                }
                """.trimIndent()
            )
        )

        val request = CreateContactsRequest(
            contacts = listOf(
                ContactCardBundle(
                    cards = listOf(
                        ContactCardDto(type = 2, data = "FN:Alice", signature = "-----BEGIN PGP SIGNATURE-----..."),
                        ContactCardDto(type = 3, data = "-----BEGIN PGP MESSAGE-----...", signature = "-----BEGIN PGP SIGNATURE-----...")
                    )
                )
            )
        )
        val response = api.createContacts(request)

        assertEquals(1000, response.code)
        assertEquals(1, response.responses.size)
        assertEquals(1000, response.responses[0].response.code)
        assertEquals("new-c1", response.responses[0].response.contact?.id)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/contacts/v4/contacts", recorded.path)
        val body = recorded.body.readUtf8()
        assertTrue("body must contain Cards", body.contains("\"Cards\""))
        assertTrue("body must contain Contacts", body.contains("\"Contacts\""))
    }

    @Test fun updateContact_sends_PUT_with_cards_body() = runTest {
        session.update(uid = "uid-x", accessToken = "access-x")
        server.enqueue(
            MockResponse().setBody(
                """
                {
                    "Code":1000,
                    "Contact":{"ID":"c1","Name":"Alice","UID":"","Size":0,
                               "CreateTime":1700000000,"ModifyTime":1700000200,
                               "Cards":[],"ContactEmails":[],"LabelIDs":[]}
                }
                """.trimIndent()
            )
        )

        val request = UpdateContactRequest(
            cards = listOf(
                ContactCardDto(type = 2, data = "FN:Alice Updated", signature = "sig...")
            )
        )
        val response = api.updateContact("c1", request)

        assertEquals(1000, response.code)
        assertEquals("c1", response.contact?.id)
        assertEquals(1_700_000_200L, response.contact?.modifyTime)

        val recorded = server.takeRequest()
        assertEquals("PUT", recorded.method)
        assertEquals("/contacts/v4/contacts/c1", recorded.path)
        val body = recorded.body.readUtf8()
        assertTrue("body must contain Cards", body.contains("\"Cards\""))
    }

    @Test fun deleteContacts_sends_PUT_with_IDs_body() = runTest {
        session.update(uid = "uid-x", accessToken = "access-x")
        server.enqueue(
            MockResponse().setBody(
                """
                {
                    "Code":1001,
                    "Responses":[
                        {"ID":"c1","Response":{"Code":1000}},
                        {"ID":"c2","Response":{"Code":1000}}
                    ]
                }
                """.trimIndent()
            )
        )

        val response = api.deleteContacts(BulkDeleteRequest(ids = listOf("c1", "c2")))

        assertEquals(1001, response.code)
        assertEquals(2, response.responses.size)
        assertEquals("c1", response.responses[0].id)
        assertEquals(1000, response.responses[0].response.code)

        val recorded = server.takeRequest()
        assertEquals("PUT", recorded.method)
        assertEquals("/contacts/v4/contacts/delete", recorded.path)
        val body = recorded.body.readUtf8()
        assertTrue("body must contain IDs", body.contains("\"IDs\""))
        assertTrue("body must contain c1", body.contains("c1"))
        assertTrue("body must contain c2", body.contains("c2"))
    }

    @Test fun listContactEmails_ignores_unknown_server_fields() = runTest {
        // Server adds a field we don't model; deserialization must not blow up.
        server.enqueue(
            MockResponse().setBody(
                """
                {
                    "Code":1000,
                    "Total":1,
                    "ContactEmails":[
                        {"ID":"e1","Email":"alice@proton.me","ContactID":"c1",
                         "Type":[],"LabelIDs":[],
                         "BrandNewServerField":"surprise"}
                    ],
                    "AnotherUnknownField":42
                }
                """.trimIndent()
            )
        )

        val response = api.listContactEmails(page = 0, pageSize = 1000)
        assertEquals(1, response.contactEmails.size)
        assertEquals("alice@proton.me", response.contactEmails[0].email)
    }
}
