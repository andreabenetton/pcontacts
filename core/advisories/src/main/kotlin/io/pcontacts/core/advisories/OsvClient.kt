// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.advisories

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** One shipped artifact, as the ADR-0024 snapshot lists it. */
data class ArtifactRef(val group: String, val name: String, val version: String) {
    val coordinate: String get() = "$group:$name:$version"
}

/** What osv.dev knows about one advisory, reduced to what the app shows. */
data class OsvVulnerability(
    val id: String,
    val aliases: List<String>,
    val severity: String?,
    val summary: String?
)

/**
 * The osv.dev API, two calls (ADR-0025): a batch query by Maven package and
 * version, and the details of one advisory. The request carries the artifact
 * coordinates and nothing else: no cookies, no custom headers, no identifiers.
 * `[A]` Response shapes follow the published OSV API and are validated against
 * the live service on the test device.
 */
class OsvClient(
    baseUrl: String = DEFAULT_BASE_URL,
    private val client: OkHttpClient = defaultClient()
) {
    private val base = baseUrl.trimEnd('/')
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        // The ecosystem is a default in the DTO; OSV needs it on the wire.
        encodeDefaults = true
    }

    /** Advisory ids per artifact, in the artifacts' order; empty lists when none. */
    suspend fun queryBatch(artifacts: List<ArtifactRef>): List<List<String>> {
        if (artifacts.isEmpty()) return emptyList()
        val body = QueryBatchRequest(
            artifacts.map { QueryEntry(PackageRef(name = "${it.group}:${it.name}"), version = it.version) }
        )
        val text = post("$base/v1/querybatch", json.encodeToString(QueryBatchRequest.serializer(), body))
        val parsed = json.decodeFromString(QueryBatchResponse.serializer(), text)
        require(parsed.results.size == artifacts.size) {
            "osv.dev returned ${parsed.results.size} results for ${artifacts.size} queries"
        }
        return parsed.results.map { result -> result.vulns.map { it.id } }
    }

    suspend fun vulnerability(id: String): OsvVulnerability {
        val text = get("$base/v1/vulns/$id")
        val v = json.decodeFromString(VulnResponse.serializer(), text)
        return OsvVulnerability(
            id = v.id,
            aliases = v.aliases,
            severity = v.databaseSpecific?.get("severity")?.jsonPrimitive?.content?.uppercase(),
            summary = v.summary
        )
    }

    private suspend fun post(url: String, body: String): String = execute(
        Request.Builder().url(url).post(body.toRequestBody(JSON_MEDIA_TYPE)).build()
    )

    private suspend fun get(url: String): String = execute(Request.Builder().url(url).get().build())

    private suspend fun execute(request: Request): String = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("osv.dev answered HTTP ${response.code}")
            response.body.string()
        }
    }

    @Serializable
    private data class QueryBatchRequest(val queries: List<QueryEntry>)

    @Serializable
    private data class QueryEntry(@SerialName("package") val pkg: PackageRef, val version: String)

    @Serializable
    private data class PackageRef(val name: String, val ecosystem: String = "Maven")

    @Serializable
    private data class QueryBatchResponse(val results: List<QueryResult> = emptyList())

    @Serializable
    private data class QueryResult(val vulns: List<VulnRef> = emptyList())

    @Serializable
    private data class VulnRef(val id: String)

    @Serializable
    private data class VulnResponse(
        val id: String,
        val summary: String? = null,
        val aliases: List<String> = emptyList(),
        @SerialName("database_specific") val databaseSpecific: JsonObject? = null
    )

    companion object {
        const val DEFAULT_BASE_URL = "https://api.osv.dev/"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val TIMEOUT_SECONDS = 20L

        /** No cookies, no cache, no redirects off the host: the guard decides what resolves. */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .dns(OsvHostDnsGuard())
            .followRedirects(false)
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }
}
