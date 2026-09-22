// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.advisories

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.UnknownHostException

class OsvClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OsvClient

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        client = OsvClient(baseUrl = server.url("/").toString(), client = OsvClient.defaultClient())
    }

    @After fun tearDown() = server.shutdown()

    @Test fun batch_query_sends_only_maven_coordinates_and_maps_ids_per_artifact() = runTest {
        server.enqueue(
            MockResponse().setBody("""{"results":[{"vulns":[{"id":"GHSA-1","modified":"2026-01-01T00:00:00Z"}]},{}]}""")
        )

        val ids = client.queryBatch(
            listOf(ArtifactRef("com.example", "lib", "1.0"), ArtifactRef("org.other", "thing", "2.3"))
        )

        assertEquals(listOf(listOf("GHSA-1"), emptyList()), ids)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/querybatch", request.path)
        assertEquals(
            """{"queries":[{"package":{"name":"com.example:lib","ecosystem":"Maven"},"version":"1.0"},""" +
                """{"package":{"name":"org.other:thing","ecosystem":"Maven"},"version":"2.3"}]}""",
            request.body.readUtf8()
        )
        assertNull(request.getHeader("Cookie"))
        assertNull(request.getHeader("Authorization"))
    }

    @Test fun vulnerability_details_reduce_to_aliases_severity_and_summary() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"id":"GHSA-1","summary":"Bad thing","aliases":["CVE-2026-1"],
                    "database_specific":{"severity":"High","cwe_ids":["CWE-1"]},"affected":[]}"""
            )
        )

        val v = client.vulnerability("GHSA-1")

        assertEquals("/v1/vulns/GHSA-1", server.takeRequest().path)
        assertEquals(OsvVulnerability("GHSA-1", listOf("CVE-2026-1"), "HIGH", "Bad thing"), v)
    }

    @Test fun a_mismatched_result_count_or_an_http_error_is_a_failure_not_a_result() = runTest {
        server.enqueue(MockResponse().setBody("""{"results":[]}"""))
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { client.queryBatch(listOf(ArtifactRef("a", "b", "1"))) }
        }
        server.enqueue(MockResponse().setResponseCode(500))
        assertThrows(java.io.IOException::class.java) {
            kotlinx.coroutines.runBlocking { client.vulnerability("GHSA-2") }
        }
    }

    @Test fun the_guard_resolves_osv_and_localhost_only() {
        val guard = OsvHostDnsGuard()
        assertTrue(guard.lookup("localhost").isNotEmpty())
        assertThrows(UnknownHostException::class.java) { guard.lookup("api.proton.me") }
        assertThrows(UnknownHostException::class.java) { guard.lookup("osv.dev") }
        assertThrows(UnknownHostException::class.java) { guard.lookup("evil.api.osv.dev") }
    }
}
