// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.advisories

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** An advisory osv.dev reports for a shipped artifact that the build-time snapshot does not list. */
@Serializable
data class Advisory(
    val coordinate: String,
    val id: String,
    val aliases: List<String> = emptyList(),
    val severity: String? = null,
    val summary: String? = null
) {
    val url: String get() = "https://osv.dev/vulnerability/$id"
}

/** One completed runtime check: when it ran and what it found. Cached as JSON by the app. */
@Serializable
data class AdvisoryResult(val checkedAtMillis: Long, val advisories: List<Advisory>) {

    fun encode(): String = codec.encodeToString(serializer(), this)

    companion object {
        private val codec = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

        fun decode(json: String): AdvisoryResult? =
            runCatching { codec.decodeFromString(serializer(), json) }.getOrNull()
    }
}

/**
 * Compares what osv.dev knows about the shipped artifacts with what the
 * snapshot already lists (ADR-0025). Details are fetched only for ids the
 * snapshot does not know, capped so a noisy answer cannot turn into a burst of
 * requests; an advisory whose alias the snapshot lists is the same finding
 * under another name and is dropped.
 */
class AdvisoryCheck(private val client: OsvClient, private val maxDetails: Int = DEFAULT_MAX_DETAILS) {

    suspend fun run(artifacts: List<ArtifactRef>, knownIds: Set<String>, nowMillis: Long): AdvisoryResult {
        val idsPerArtifact = client.queryBatch(artifacts)
        val advisories = mutableListOf<Advisory>()
        var detailsFetched = 0
        for ((artifact, ids) in artifacts.zip(idsPerArtifact)) {
            for (id in ids.filter { it !in knownIds }.distinct()) {
                val advisory = if (detailsFetched < maxDetails) {
                    detailsFetched++
                    withDetails(artifact, id, knownIds)
                } else {
                    Advisory(artifact.coordinate, id)
                }
                if (advisory != null) advisories += advisory
            }
        }
        return AdvisoryResult(nowMillis, advisories)
    }

    /** Null when the advisory is a known finding under another name. */
    private suspend fun withDetails(artifact: ArtifactRef, id: String, knownIds: Set<String>): Advisory? {
        val details = client.vulnerability(id)
        if (details.aliases.any { it in knownIds }) return null
        return Advisory(artifact.coordinate, id, details.aliases, details.severity, details.summary)
    }

    companion object {
        const val DEFAULT_MAX_DETAILS = 20
    }
}
