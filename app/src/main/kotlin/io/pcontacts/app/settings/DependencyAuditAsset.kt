// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.app.settings

import android.content.Context
import io.pcontacts.feature.settings.AuditCve
import io.pcontacts.feature.settings.AuditedDependency
import io.pcontacts.feature.settings.DependencyAudit
import org.json.JSONObject

/**
 * Reads the audit snapshot the build committed to `assets/dependency-audit.json`
 * (ADR-0024, written by `:app:dependencyAudit`). Platform JSON only: no new
 * dependency for a file that describes the dependencies.
 */
object DependencyAuditAsset {

    const val FILE_NAME = "dependency-audit.json"

    fun load(context: Context): DependencyAudit =
        parse(context.assets.open(FILE_NAME).bufferedReader().use { it.readText() })

    fun parse(json: String): DependencyAudit {
        val root = JSONObject(json)
        val deps = root.getJSONArray("dependencies")
        return DependencyAudit(
            generatedAt = root.getString("generatedAt"),
            nvdDataAsOf = root.stringOrNull("nvdDataAsOf"),
            dependencies = List(deps.length()) { i -> dependency(deps.getJSONObject(i)) }
        )
    }

    private fun dependency(o: JSONObject): AuditedDependency {
        val licenses = o.getJSONArray("licenses")
        val cves = o.getJSONArray("cves")
        return AuditedDependency(
            group = o.getString("group"),
            name = o.getString("name"),
            version = o.getString("version"),
            licenses = List(licenses.length()) { licenses.getString(it) },
            cves = List(cves.length()) { cve(cves.getJSONObject(it)) }
        )
    }

    private fun cve(o: JSONObject): AuditCve = AuditCve(
        id = o.getString("id"),
        score = if (o.isNull("score")) null else o.getDouble("score"),
        severity = o.stringOrNull("severity"),
        url = o.getString("url"),
        suppressed = o.getBoolean("suppressed"),
        reason = o.stringOrNull("reason"),
        falsePositive = o.optBoolean("falsePositive", false)
    )

    /** `optString` would turn a JSON null into the text "null"; the snapshot uses null for "none". */
    private fun JSONObject.stringOrNull(key: String): String? =
        if (isNull(key)) null else getString(key).ifEmpty { null }
}
