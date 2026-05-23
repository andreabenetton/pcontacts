// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.feature.settings

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    @Test fun triggerSyncNow_transitions_idle_syncing_done() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val vm = SettingsViewModel(
            syncNow = { SettingsActionResult.Success(message = "Sync requested") },
            signOut = { error("not used") },
            scope = TestScope(dispatcher),
            workDispatcher = dispatcher
        )
        assertEquals(SettingsUiState.Idle, vm.uiState.value)

        vm.triggerSyncNow()
        assertEquals(SettingsUiState.Syncing, vm.uiState.value)

        advanceUntilIdle()
        assertEquals(SettingsUiState.SyncDone(message = "Sync requested"), vm.uiState.value)
    }

    @Test fun triggerSyncNow_failure_surfaces_reason() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val vm = SettingsViewModel(
            syncNow = { SettingsActionResult.Failure(reason = "no_account") },
            signOut = { error("not used") },
            scope = TestScope(dispatcher),
            workDispatcher = dispatcher
        )
        vm.triggerSyncNow(); advanceUntilIdle()
        assertEquals(SettingsUiState.SyncFailed(reason = "no_account"), vm.uiState.value)
    }

    @Test fun triggerSignOut_transitions_idle_signingOut_signedOut() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val vm = SettingsViewModel(
            syncNow = { error("not used") },
            signOut = { SettingsActionResult.Success() },
            scope = TestScope(dispatcher),
            workDispatcher = dispatcher
        )
        vm.triggerSignOut()
        assertEquals(SettingsUiState.SigningOut, vm.uiState.value)
        advanceUntilIdle()
        assertEquals(SettingsUiState.SignedOut, vm.uiState.value)
    }

    @Test fun second_tap_while_busy_is_a_noop() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val gate = CompletableDeferred<SettingsActionResult>()
        var callCount = 0
        val vm = SettingsViewModel(
            syncNow = {
                callCount += 1
                gate.await()
            },
            signOut = { error("not used") },
            scope = TestScope(dispatcher),
            workDispatcher = dispatcher
        )
        vm.triggerSyncNow(); advanceUntilIdle()
        assertEquals(SettingsUiState.Syncing, vm.uiState.value)
        assertEquals(1, callCount)

        vm.triggerSyncNow()    // should be ignored
        advanceUntilIdle()
        assertEquals(SettingsUiState.Syncing, vm.uiState.value)
        assertEquals(1, callCount)

        gate.complete(SettingsActionResult.Success())
        advanceUntilIdle()
        assertTrue(vm.uiState.value is SettingsUiState.SyncDone)
    }

    @Test fun reset_returns_to_idle() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val vm = SettingsViewModel(
            syncNow = { SettingsActionResult.Failure("x") },
            signOut = { error("not used") },
            scope = TestScope(dispatcher),
            workDispatcher = dispatcher
        )
        vm.triggerSyncNow(); advanceUntilIdle()
        assertTrue(vm.uiState.value is SettingsUiState.SyncFailed)
        vm.reset()
        assertEquals(SettingsUiState.Idle, vm.uiState.value)
    }
}
