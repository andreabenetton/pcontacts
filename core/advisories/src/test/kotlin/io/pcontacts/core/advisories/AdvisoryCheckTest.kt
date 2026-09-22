// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.advisories

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class AdvisoryCheckTest {

    private lateinit var server: MockWebServer
    private val vulns = mutableMapOf<String, String>()
    private var batchBody = """{"results":[]}"""

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/v1/querybatch" -> MockResponse().setBody(batchBody)
                request.path!!.startsWith("/v1/vulns/") -> vulns[request.path!!.substringAfterLast('/')]
                    ?.let { MockResponse().setBody(it) } ?: MockResponse().setResponseCode(404)
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
    }

    @After fun tearDown() = server.shutdown()

    private fun check(maxDetails: Int = AdvisoryCheck.DEFAULT_MAX_DETAILS) =
        AdvisoryCheck(OsvClient(server.url("/").toString()), maxDetails)

    private val artifacts = listOf(ArtifactRef("com.example", "lib", "1.0"), ArtifactRef("org.other", "thing", "2.3"))

    @Test fun known_ids_and_known_aliases_are_not_new() = runTest {
        batchBody = """{"results":[{"vulns":[{"id":"GHSA-known"},{"id":"GHSA-alias"},{"id":"GHSA-new"}]},{}]}"""
        vulns["GHSA-alias"] = """{"id":"GHSA-alias","aliases":["CVE-2020-1"]}"""
        vulns["GHSA-new"] = """{"id":"GHSA-new","summary":"New","aliases":["CVE-2026-9"],"database_specific":{"severity":"MODERATE"}}"""

        val result = check().run(artifacts, knownIds = setOf("GHSA-known", "CVE-2020-1"), nowMillis = 42L)

        assertEquals(42L, result.checkedAtMillis)
        assertEquals(
            listOf(Advisory("com.example:lib:1.0", "GHSA-new", listOf("CVE-2026-9"), "MODERATE", "New")),
            result.advisories
        )
        assertEquals("https://osv.dev/vulnerability/GHSA-new", result.advisories.single().url)
    }

    @Test fun beyond_the_details_cap_ids_are_still_reported_without_details() = runTest {
        batchBody = """{"results":[{"vulns":[{"id":"GHSA-a"},{"id":"GHSA-b"}]},{"vulns":[{"id":"GHSA-c"}]}]}"""
        vulns["GHSA-a"] = """{"id":"GHSA-a","summary":"A"}"""

        val result = check(maxDetails = 1).run(artifacts, knownIds = emptySet(), nowMillis = 1L)

        assertEquals(listOf("GHSA-a", "GHSA-b", "GHSA-c"), result.advisories.map { it.id })
        assertEquals("A", result.advisories[0].summary)
        assertEquals(null, result.advisories[1].summary)
        assertEquals("org.other:thing:2.3", result.advisories[2].coordinate)
    }

    @Test fun the_result_survives_a_round_trip_through_its_json() {
        val result = AdvisoryResult(7L, listOf(Advisory("a:b:1", "GHSA-x", listOf("CVE-1"), "HIGH", "s")))
        assertEquals(result, AdvisoryResult.decode(result.encode()))
        assertEquals(null, AdvisoryResult.decode("not json"))
    }
}
