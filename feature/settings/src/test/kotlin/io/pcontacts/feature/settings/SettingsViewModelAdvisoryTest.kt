// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.feature.settings

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelAdvisoryTest {

    private val found = AdvisoryCheckState(
        enabled = true,
        lastCheckedAtMillis = 99L,
        advisories = listOf(RuntimeAdvisory("a:b:1", "GHSA-1", "https://osv.dev/vulnerability/GHSA-1", "HIGH", null))
    )

    @Test fun the_switch_persists_runs_a_first_check_and_off_drops_the_result() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val persisted = mutableListOf<Boolean>()
        var checks = 0
        val vm = SettingsViewModel(
            syncNow = { SettingsActionResult.Success() },
            signOut = { SettingsActionResult.Success() },
            setAdvisoryCheckEnabled = { persisted += it },
            runAdvisoryCheck = {
                checks++
                found
            },
            scope = TestScope(dispatcher),
            workDispatcher = dispatcher
        )
        advanceUntilIdle()
        assertEquals(AdvisoryCheckState.OFF, vm.advisoryState.value)

        vm.setAdvisoryCheck(true)
        assertTrue(vm.advisoryState.value.enabled)
        advanceUntilIdle()
        assertEquals(listOf(true), persisted)
        assertEquals(1, checks)
        assertEquals(found, vm.advisoryState.value)
        assertFalse(vm.advisoryChecking.value)

        vm.setAdvisoryCheck(false)
        advanceUntilIdle()
        assertEquals(listOf(true, false), persisted)
        assertEquals(AdvisoryCheckState.OFF, vm.advisoryState.value)
        assertEquals(1, checks)
    }

    @Test fun a_failing_check_keeps_the_previous_state() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val vm = SettingsViewModel(
            syncNow = { SettingsActionResult.Success() },
            signOut = { SettingsActionResult.Success() },
            queryAdvisoryState = { found },
            runAdvisoryCheck = { error("offline") },
            scope = TestScope(dispatcher),
            workDispatcher = dispatcher
        )
        advanceUntilIdle()
        vm.checkAdvisoriesNow()
        assertTrue(vm.advisoryChecking.value)
        advanceUntilIdle()
        assertEquals(found, vm.advisoryState.value)
        assertFalse(vm.advisoryChecking.value)
    }
}
