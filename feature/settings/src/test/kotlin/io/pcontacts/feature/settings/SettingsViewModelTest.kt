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

    @Test fun verification_stats_loaded_on_init() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val stats = VerificationStats(totalContacts = 10, unverifiedContacts = 2)
        val vm = SettingsViewModel(
            syncNow = { error("not used") },
            signOut = { error("not used") },
            queryVerificationStats = { stats },
            scope = TestScope(dispatcher),
            workDispatcher = dispatcher
        )
        advanceUntilIdle()
        assertEquals(stats, vm.verificationStats.value)
    }

    @Test fun verification_stats_refreshed_after_sync() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var callCount = 0
        val vm = SettingsViewModel(
            syncNow = { SettingsActionResult.Success("done") },
            signOut = { error("not used") },
            queryVerificationStats = {
                callCount += 1
                VerificationStats(totalContacts = 10, unverifiedContacts = callCount)
            },
            scope = TestScope(dispatcher),
            workDispatcher = dispatcher
        )
        advanceUntilIdle()
        assertEquals(1, vm.verificationStats.value?.unverifiedContacts)

        vm.triggerSyncNow()
        advanceUntilIdle()
        assertEquals(2, vm.verificationStats.value?.unverifiedContacts)
    }

    @Test fun verification_stats_null_when_query_fails() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val vm = SettingsViewModel(
            syncNow = { error("not used") },
            signOut = { error("not used") },
            queryVerificationStats = { error("db error") },
            scope = TestScope(dispatcher),
            workDispatcher = dispatcher
        )
        advanceUntilIdle()
        assertEquals(null, vm.verificationStats.value)
    }

    @Test fun sync_interval_defaults_to_initial_value() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val vm = SettingsViewModel(
            syncNow = { error("not used") },
            signOut = { error("not used") },
            initialSyncIntervalHours = 6,
            scope = TestScope(dispatcher),
            workDispatcher = dispatcher
        )
        assertEquals(SyncInterval.SIX_HOURS, vm.syncInterval.value)
    }

    @Test fun set_sync_interval_updates_state_and_calls_callback() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var captured: Long? = null
        val vm = SettingsViewModel(
            syncNow = { error("not used") },
            signOut = { error("not used") },
            onSyncIntervalChanged = { captured = it },
            scope = TestScope(dispatcher),
            workDispatcher = dispatcher
        )
        vm.setSyncInterval(SyncInterval.ONE_HOUR)
        assertEquals(SyncInterval.ONE_HOUR, vm.syncInterval.value)
        assertEquals(1L, captured)
    }
}
