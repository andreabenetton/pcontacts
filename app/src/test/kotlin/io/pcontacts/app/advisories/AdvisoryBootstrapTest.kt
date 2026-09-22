// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.app.advisories

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import io.pcontacts.core.advisories.Advisory
import io.pcontacts.core.advisories.AdvisoryResult
import io.pcontacts.core.advisories.ArtifactRef
import io.pcontacts.core.storage.InMemoryUserPreferences
import io.pcontacts.feature.settings.AuditCve
import io.pcontacts.feature.settings.AuditStatus
import io.pcontacts.feature.settings.AuditedDependency
import io.pcontacts.feature.settings.DependencyAudit
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class AdvisoryBootstrapTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
    }

    private val known = AuditCve(
        id = "CVE-2015-1",
        score = 9.8,
        severity = "HIGH",
        url = "https://nvd.nist.gov/vuln/detail/CVE-2015-1",
        suppressed = true,
        reason = "fp",
        falsePositive = true
    )
    private val audit = DependencyAudit(
        "2026-09-22",
        null,
        listOf(
            AuditedDependency("com.example", "lib", "1.0", emptyList(), listOf(known)),
            AuditedDependency("org.other", "thing", "2.3", emptyList(), emptyList())
        )
    )
    private val found = Advisory("com.example:lib:1.0", "GHSA-1", listOf("CVE-2026-1"), "HIGH", "summary")

    @Test fun a_check_sends_the_snapshot_artifacts_and_known_ids_caches_the_result_and_announces_once() = runTest {
        val prefs = InMemoryUserPreferences().apply { advisoryCheckEnabled = true }
        var sent: Pair<List<ArtifactRef>, Set<String>>? = null
        val notified = mutableListOf<Int>()
        val check: suspend (List<ArtifactRef>, Set<String>) -> AdvisoryResult = { artifacts, ids ->
            sent = artifacts to ids
            AdvisoryResult(77L, listOf(found))
        }

        val state = AdvisoryBootstrap.runCheck(app, prefs, audit, check) { notified += it }
        AdvisoryBootstrap.runCheck(app, prefs, audit, check) { notified += it }

        assertEquals(
            listOf(ArtifactRef("com.example", "lib", "1.0"), ArtifactRef("org.other", "thing", "2.3")),
            sent!!.first
        )
        assertEquals(setOf("CVE-2015-1"), sent!!.second)
        assertEquals(listOf(1), notified)
        assertEquals(77L, state.lastCheckedAtMillis)
        assertEquals("GHSA-1", state.advisories.single().id)
        assertEquals(state, AdvisoryBootstrap.state(app, prefs))
        assertEquals(AuditStatus.OPEN, audit.withRuntime(state).status)
    }

    @Test fun muting_makes_the_advisory_assessed_and_a_version_bump_drops_the_mute() = runTest {
        val prefs = InMemoryUserPreferences().apply { advisoryCheckEnabled = true }
        val check: suspend (List<ArtifactRef>, Set<String>) -> AdvisoryResult = { _, _ ->
            AdvisoryResult(1L, listOf(found))
        }
        val state = AdvisoryBootstrap.runCheck(app, prefs, audit, check) {}

        val muted = AdvisoryBootstrap.setMuted(app, state.advisories.single(), true, prefs)
        assertTrue(muted.advisories.single().muted)
        assertEquals(AuditStatus.ASSESSED, audit.withRuntime(muted).status)
        assertEquals("com.example:lib:1.0|GHSA-1", prefs.advisoryMutedKeys)

        // The next release ships lib 1.1: the mute for 1.0 is stale and goes away on the next check.
        val bumped = audit.copy(
            dependencies = listOf(AuditedDependency("com.example", "lib", "1.1", emptyList(), emptyList()))
        )
        val bumpedFound = found.copy(coordinate = "com.example:lib:1.1")
        val bumpedCheck: suspend (List<ArtifactRef>, Set<String>) -> AdvisoryResult = { _, _ ->
            AdvisoryResult(2L, listOf(bumpedFound))
        }
        val after = AdvisoryBootstrap.runCheck(app, prefs, bumped, bumpedCheck) {}
        assertEquals("", prefs.advisoryMutedKeys)
        assertFalse(after.advisories.single().muted)
        assertEquals(AuditStatus.OPEN, bumped.withRuntime(after).status)
    }

    @Test fun switching_off_drops_everything_and_the_state_reads_off() {
        val prefs = InMemoryUserPreferences().apply {
            advisoryCheckEnabled = true
            advisoryResultJson = AdvisoryResult(5L, listOf(found)).encode()
            lastAdvisoryCheckAtMillis = 5L
            advisoryNotifiedIds = "GHSA-1"
            advisoryMutedKeys = "com.example:lib:1.0|GHSA-1"
        }

        AdvisoryBootstrap.setEnabled(app, false, prefs)

        assertFalse(prefs.advisoryCheckEnabled)
        assertEquals(null, prefs.advisoryResultJson)
        assertEquals(0L, prefs.lastAdvisoryCheckAtMillis)
        assertEquals("", prefs.advisoryNotifiedIds)
        assertEquals("", prefs.advisoryMutedKeys)
        assertFalse(AdvisoryBootstrap.state(app, prefs).enabled)
    }
}
