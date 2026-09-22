// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.feature.settings

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

    /** Every id the snapshot lists, open or assessed: what a runtime result is compared with. */
    val knownIds: Set<String> get() = dependencies.flatMap { d -> d.cves.map { it.id } }.toSet()

    /**
     * The snapshot plus what the opt-in runtime check found (ADR-0025): each advisory becomes an
     * open entry of its artifact, so the dot, the order and the screen treat it like any open CVE.
     */
    fun withRuntime(runtime: AdvisoryCheckState): DependencyAudit {
        if (runtime.advisories.isEmpty()) return this
        val byCoordinate = runtime.advisories.groupBy { it.coordinate }
        return copy(
            dependencies = dependencies.map { d ->
                val extra = byCoordinate[d.coordinate].orEmpty().map { a ->
                    AuditCve(a.id, null, a.severity, a.url, suppressed = a.muted, reason = a.summary, runtime = true)
                }
                if (extra.isEmpty()) d else d.copy(cves = d.cves + extra)
            }
        )
    }
}

data class AuditedDependency(
    val group: String,
    val name: String,
    val version: String,
    val licenses: List<String>,
    val cves: List<AuditCve>
) {
    val coordinate: String get() = "$group:$name:$version"

    /** A scanner false positive is not a CVE of this artifact, so it does not colour it. */
    val status: AuditStatus = when {
        cves.any { !it.suppressed } -> AuditStatus.OPEN
        cves.any { it.suppressed && !it.falsePositive } -> AuditStatus.ASSESSED
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
    val reason: String?,
    /** The scanner matched a different product than this artifact (a CPE mismatch). */
    val falsePositive: Boolean = false,
    /** Found by the runtime check against osv.dev, not by the build-time scan (ADR-0025). */
    val runtime: Boolean = false
)

/**
 * An advisory the runtime check found that the snapshot does not list. [muted] is the user's
 * own assessment: a muted advisory counts as assessed (amber), an unmuted one as open (red).
 */
data class RuntimeAdvisory(
    val coordinate: String,
    val id: String,
    val url: String,
    val severity: String?,
    val summary: String?,
    val muted: Boolean = false
) {
    /** The mute key: the coordinate carries the version, so a bump invalidates the mute. */
    val key: String get() = "$coordinate|$id"
}

/** The opt-in runtime check (ADR-0025): its switch, when it last ran and what it found. */
data class AdvisoryCheckState(
    val enabled: Boolean,
    val lastCheckedAtMillis: Long,
    val advisories: List<RuntimeAdvisory>
) {
    companion object {
        val OFF = AdvisoryCheckState(enabled = false, lastCheckedAtMillis = 0L, advisories = emptyList())
    }
}

/** Green, amber, red — in that order, so the ordinal is the severity. */
enum class AuditStatus { CLEAN, ASSESSED, OPEN }

/** What the app bar shows next to the version: the status dot and where a tap goes. */
data class AuditIndicator(val status: AuditStatus, val onOpen: () -> Unit)

/** Fixed, saturated colours: the theme's error tone is a pale pink in the dark theme and blends with the accents. */
@Composable
internal fun AuditStatus.tint(): Color = when (this) {
    AuditStatus.CLEAN -> AuditGreen
    AuditStatus.ASSESSED -> AuditAmber
    AuditStatus.OPEN -> AuditRed
}

internal fun AuditStatus.labelRes(): Int = when (this) {
    AuditStatus.CLEAN -> R.string.dependencies_status_clean
    AuditStatus.ASSESSED -> R.string.dependencies_status_assessed
    AuditStatus.OPEN -> R.string.dependencies_status_open
}

/** The short label of the chip next to the version. */
internal fun AuditStatus.chipRes(): Int = when (this) {
    AuditStatus.CLEAN -> R.string.dependencies_chip_clean
    AuditStatus.ASSESSED -> R.string.dependencies_chip_assessed
    AuditStatus.OPEN -> R.string.dependencies_chip_open
}

private val AuditGreen = Color(0xFF34C759)
private val AuditAmber = Color(0xFFFFA000)
private val AuditRed = Color(0xFFFF3B30)
