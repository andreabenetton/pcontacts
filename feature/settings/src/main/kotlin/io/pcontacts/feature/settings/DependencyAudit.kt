// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.feature.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The build-time dependency audit the app ships (ADR-0024): what is on the
 * release classpath and which known CVEs matched it when the snapshot was
 * taken. Nothing here is looked up at runtime.
 */
data class DependencyAudit(
    /** Date the snapshot was generated, ISO-8601 day. */
    val generatedAt: String,
    /** Timestamp of the NVD data the scan used, when the report carried one. */
    val nvdDataAsOf: String?,
    val dependencies: List<AuditedDependency>
) {
    val status: AuditStatus = when {
        dependencies.any { it.status == AuditStatus.OPEN } -> AuditStatus.OPEN
        dependencies.any { it.status == AuditStatus.ASSESSED } -> AuditStatus.ASSESSED
        else -> AuditStatus.CLEAN
    }

    /** Problem artifacts first (open before assessed), then the rest, each group by coordinate. */
    val sortedForDisplay: List<AuditedDependency> =
        dependencies.sortedWith(compareBy({ it.status.ordinal.unaryMinus() }, { it.coordinate }))
}

data class AuditedDependency(
    val group: String,
    val name: String,
    val version: String,
    val licenses: List<String>,
    val cves: List<AuditCve>
) {
    val coordinate: String get() = "$group:$name:$version"

    val status: AuditStatus = when {
        cves.any { !it.suppressed } -> AuditStatus.OPEN
        cves.isNotEmpty() -> AuditStatus.ASSESSED
        else -> AuditStatus.CLEAN
    }
}

data class AuditCve(
    val id: String,
    val score: Double?,
    val severity: String?,
    val url: String,
    /** Matched but assessed as not applicable; [reason] is the suppression note. */
    val suppressed: Boolean,
    val reason: String?
)

/** Green, amber, red — in that order, so the ordinal is the severity. */
enum class AuditStatus { CLEAN, ASSESSED, OPEN }

/** What the app bar shows next to the version: the status dot and where a tap goes. */
data class AuditIndicator(val status: AuditStatus, val onOpen: () -> Unit)

@Composable
internal fun AuditStatus.tint(): Color = when (this) {
    AuditStatus.CLEAN -> AuditGreen
    AuditStatus.ASSESSED -> AuditAmber
    AuditStatus.OPEN -> MaterialTheme.colorScheme.error
}

internal fun AuditStatus.labelRes(): Int = when (this) {
    AuditStatus.CLEAN -> R.string.dependencies_status_clean
    AuditStatus.ASSESSED -> R.string.dependencies_status_assessed
    AuditStatus.OPEN -> R.string.dependencies_status_open
}

private val AuditGreen = Color(0xFF2E9E5B)
private val AuditAmber = Color(0xFFE0A100)
