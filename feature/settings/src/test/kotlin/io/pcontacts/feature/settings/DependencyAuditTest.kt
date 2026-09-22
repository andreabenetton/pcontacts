// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class DependencyAuditTest {

    private companion object {
        const val NVD = "https://nvd.nist.gov/vuln/detail/"
    }

    private val clean = AuditedDependency(
        "androidx.activity",
        "activity",
        "1.9.3",
        listOf("Apache-2.0"),
        emptyList()
    )
    private val assessed = AuditedDependency(
        "androidx.sqlite",
        "sqlite-android",
        "2.5.1",
        listOf("Apache-2.0"),
        listOf(AuditCve("CVE-2015-5895", 10.0, "HIGH", NVD + "CVE-2015-5895", true, "false positive"))
    )
    private val open = AuditedDependency(
        "com.example",
        "lib",
        "1.0",
        emptyList(),
        listOf(
            AuditCve("CVE-2026-1", 7.5, "HIGH", NVD + "CVE-2026-1", false, null),
            AuditCve("CVE-2026-2", 4.0, "MEDIUM", NVD + "CVE-2026-2", true, "n/a")
        )
    )

    @Test fun status_is_the_worst_of_the_dependencies() {
        assertEquals(AuditStatus.CLEAN, DependencyAudit("2026-09-22", null, listOf(clean)).status)
        assertEquals(AuditStatus.ASSESSED, DependencyAudit("2026-09-22", null, listOf(clean, assessed)).status)
        assertEquals(AuditStatus.OPEN, DependencyAudit("2026-09-22", null, listOf(assessed, open, clean)).status)
    }

    @Test fun a_scanner_false_positive_leaves_the_dependency_green() {
        val mismatch = assessed.copy(cves = assessed.cves.map { it.copy(falsePositive = true) })
        assertEquals(AuditStatus.CLEAN, mismatch.status)
        assertEquals(AuditStatus.CLEAN, DependencyAudit("2026-09-22", null, listOf(clean, mismatch)).status)
    }

    @Test fun one_open_cve_makes_the_dependency_open_whatever_else_is_suppressed() {
        assertEquals(AuditStatus.OPEN, open.status)
        assertEquals(AuditStatus.ASSESSED, assessed.status)
        assertEquals(AuditStatus.CLEAN, clean.status)
    }

    @Test fun runtime_advisories_join_their_artifact_as_open_entries_and_turn_the_status_red() {
        val url = "https://osv.dev/vulnerability/GHSA-x"
        val runtime = AdvisoryCheckState(
            enabled = true,
            lastCheckedAtMillis = 1L,
            advisories = listOf(RuntimeAdvisory(clean.coordinate, "GHSA-x", url, "HIGH", "s"))
        )
        val merged = DependencyAudit("2026-09-22", null, listOf(clean, assessed)).withRuntime(runtime)

        assertEquals(AuditStatus.OPEN, merged.status)
        val cve = merged.dependencies.single { it.coordinate == clean.coordinate }.cves.single()
        assertEquals(AuditCve("GHSA-x", null, "HIGH", url, suppressed = false, reason = "s", runtime = true), cve)
        assertEquals(setOf("CVE-2015-5895"), DependencyAudit("2026-09-22", null, listOf(clean, assessed)).knownIds)
        // While the check is on, the build-time entries no longer count: the assessed artifact reads clean.
        assertEquals(AuditStatus.CLEAN, merged.dependencies.single { it.coordinate == assessed.coordinate }.status)
        assertEquals(listOf(clean.coordinate, assessed.coordinate), merged.sortedForDisplay.map { it.coordinate })
        assertEquals(
            DependencyAudit("2026-09-22", null, listOf(clean, assessed)),
            DependencyAudit("2026-09-22", null, listOf(clean, assessed)).withRuntime(AdvisoryCheckState.OFF)
        )
    }

    @Test fun a_muted_runtime_advisory_counts_as_assessed() {
        val muted = RuntimeAdvisory(clean.coordinate, "GHSA-m", "u", null, null, muted = true)
        val state = AdvisoryCheckState(enabled = true, lastCheckedAtMillis = 1L, advisories = listOf(muted))
        val merged = DependencyAudit("2026-09-22", null, listOf(clean)).withRuntime(state)
        assertEquals(AuditStatus.ASSESSED, merged.status)
        assertEquals("a:b:1|GHSA-1", RuntimeAdvisory("a:b:1", "GHSA-1", "u", null, null).key)
    }

    @Test fun display_order_puts_open_first_then_assessed_then_the_rest_by_coordinate() {
        val audit = DependencyAudit("2026-09-22", null, listOf(clean, assessed, open))
        assertEquals(listOf(open, assessed, clean), audit.sortedForDisplay)
    }
}
