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
import org.junit.Assert.assertFalse
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

    @Test fun outbox_stats_loaded_on_init() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val vm = SettingsViewModel(
            syncNow = { error("not used") },
            signOut = { error("not used") },
            queryOutboxStats = { OutboxStats(pending = 3, quarantined = 1) },
            scope = TestScope(dispatcher),
            workDispatcher = dispatcher
        )
        advanceUntilIdle()
        assertEquals(3, vm.outboxStats.value.pending)
        assertEquals(1, vm.outboxStats.value.quarantined)
    }

    @Test fun pending_deletes_loaded_on_init() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val deletes = listOf(PendingDelete("ct-1", 1000L), PendingDelete("ct-2", 2000L))
        val vm = SettingsViewModel(
            syncNow = { error("not used") },
            signOut = { error("not used") },
            queryPendingDeletes = { deletes },
            scope = TestScope(dispatcher),
            workDispatcher = dispatcher
        )
        advanceUntilIdle()
        assertEquals(2, vm.pendingDeletes.value.size)
        assertEquals("ct-1", vm.pendingDeletes.value[0].protonContactId)
    }

    @Test fun conflicts_loaded_on_init() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val conflicts = listOf(ConflictInfo("ct-1", "Alice", "fullName"))
        val vm = SettingsViewModel(
            syncNow = { error("not used") },
            signOut = { error("not used") },
            queryConflicts = { conflicts },
            scope = TestScope(dispatcher),
            workDispatcher = dispatcher
        )
        advanceUntilIdle()
        assertEquals(1, vm.conflicts.value.size)
        assertEquals("Alice", vm.conflicts.value[0].displayName)
    }

    @Test fun cancel_pending_delete_calls_seam_and_refreshes() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val cancelled = mutableListOf<String>()
        var queryCount = 0
        val vm = SettingsViewModel(
            syncNow = { error("not used") },
            signOut = { error("not used") },
            queryPendingDeletes = {
                queryCount++
                if (queryCount == 1) listOf(PendingDelete("ct-1", 1000L))
                else emptyList()
            },
            cancelDelete = { cancelled += it },
            scope = TestScope(dispatcher),
            workDispatcher = dispatcher
        )
        advanceUntilIdle()
        assertEquals(1, vm.pendingDeletes.value.size)

        vm.cancelPendingDelete("ct-1")
        advanceUntilIdle()
        assertEquals(listOf("ct-1"), cancelled)
        assertEquals(0, vm.pendingDeletes.value.size)
    }

    @Test fun resolve_conflict_calls_seam_and_refreshes() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val resolved = mutableListOf<Pair<String, ConflictResolution>>()
        var queryCount = 0
        val vm = SettingsViewModel(
            syncNow = { error("not used") },
            signOut = { error("not used") },
            queryConflicts = {
                queryCount++
                if (queryCount == 1) listOf(ConflictInfo("ct-1", "Alice", "fullName"))
                else emptyList()
            },
            resolveConflict = { id, res -> resolved += id to res },
            scope = TestScope(dispatcher),
            workDispatcher = dispatcher
        )
        advanceUntilIdle()
        assertEquals(1, vm.conflicts.value.size)

        vm.resolveContactConflict("ct-1", ConflictResolution.USE_LOCAL)
        advanceUntilIdle()
        assertEquals(listOf("ct-1" to ConflictResolution.USE_LOCAL), resolved)
        assertEquals(0, vm.conflicts.value.size)
    }

    @Test fun outbox_stats_refreshed_after_sync() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var queryCount = 0
        val vm = SettingsViewModel(
            syncNow = { SettingsActionResult.Success("done") },
            signOut = { error("not used") },
            queryOutboxStats = {
                queryCount++
                OutboxStats(pending = if (queryCount == 1) 5 else 0, quarantined = 0)
            },
            scope = TestScope(dispatcher),
            workDispatcher = dispatcher
        )
        advanceUntilIdle()
        assertEquals(5, vm.outboxStats.value.pending)

        vm.triggerSyncNow()
        advanceUntilIdle()
        assertEquals(0, vm.outboxStats.value.pending)
    }

    @Test fun sign_out_failure_surfaces_reason() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val vm = SettingsViewModel(
            syncNow = { error("not used") },
            signOut = { SettingsActionResult.Failure("missing_contacts_permission") },
            scope = TestScope(dispatcher),
            workDispatcher = dispatcher
        )
        vm.triggerSignOut()
        assertEquals(SettingsUiState.SigningOut, vm.uiState.value)
        advanceUntilIdle()
        assertEquals(
            SettingsUiState.SignOutFailed("missing_contacts_permission"),
            vm.uiState.value
        )
    }

    @Test fun contacts_access_apps_loaded_on_init() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val apps = listOf(
            ContactsAccessApp("WhatsApp", "com.whatsapp"),
            ContactsAccessApp("Telegram", "org.telegram.messenger")
        )
        val vm = SettingsViewModel(
            syncNow = { error("not used") },
            signOut = { error("not used") },
            queryContactsAccessApps = { apps },
            scope = TestScope(dispatcher),
            workDispatcher = dispatcher
        )
        advanceUntilIdle()
        assertEquals(2, vm.contactsAccessApps.value.size)
        assertEquals("WhatsApp", vm.contactsAccessApps.value[0].appName)
        assertEquals("com.whatsapp", vm.contactsAccessApps.value[0].packageName)
    }

    @Test fun contacts_access_apps_empty_on_query_failure() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val vm = SettingsViewModel(
            syncNow = { error("not used") },
            signOut = { error("not used") },
            queryContactsAccessApps = { error("pm error") },
            scope = TestScope(dispatcher),
            workDispatcher = dispatcher
        )
        advanceUntilIdle()
        assertEquals(emptyList<ContactsAccessApp>(), vm.contactsAccessApps.value)
    }

    @Test fun quarantined_changes_load_on_refresh() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val vm = SettingsViewModel(
            syncNow = { error("not used") },
            signOut = { error("not used") },
            queryQuarantinedChanges = { listOf(sampleQuarantined(1L, "Alice")) },
            scope = TestScope(dispatcher),
            workDispatcher = dispatcher
        )
        advanceUntilIdle()

        assertEquals(1, vm.quarantinedChanges.value.size)
        assertEquals("Alice", vm.quarantinedChanges.value.single().displayName)
        assertEquals(QuarantinedOperation.UPDATE, vm.quarantinedChanges.value.single().operation)
    }

    @Test fun quarantined_changes_default_on_query_failure() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val vm = SettingsViewModel(
            syncNow = { error("not used") },
            signOut = { error("not used") },
            queryQuarantinedChanges = { error("db error") },
            scope = TestScope(dispatcher),
            workDispatcher = dispatcher
        )
        advanceUntilIdle()
        assertEquals(emptyList<QuarantinedChange>(), vm.quarantinedChanges.value)
    }

    @Test fun retry_quarantined_calls_seam_and_refreshes() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val retried = mutableListOf<Long>()
        var queryCount = 0
        val vm = SettingsViewModel(
            syncNow = { error("not used") },
            signOut = { error("not used") },
            queryQuarantinedChanges = {
                queryCount++
                if (queryCount == 1) {
                    listOf(sampleQuarantined(7L, "Alice"), sampleQuarantined(8L, "Bob"))
                } else {
                    listOf(sampleQuarantined(8L, "Bob"))
                }
            },
            retryQuarantinedChange = { retried += it },
            scope = TestScope(dispatcher),
            workDispatcher = dispatcher
        )
        advanceUntilIdle()
        vm.showQuarantinedChangesDialog()

        vm.retryQuarantined(7L)
        advanceUntilIdle()

        assertEquals(listOf(7L), retried)
        assertEquals(listOf(8L), vm.quarantinedChanges.value.map { it.outboxId })
        assertTrue(vm.quarantinedDialogOpen.value)
    }

    @Test fun discard_quarantined_closes_dialog_once_the_list_empties() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val discarded = mutableListOf<Long>()
        var queryCount = 0
        val vm = SettingsViewModel(
            syncNow = { error("not used") },
            signOut = { error("not used") },
            queryQuarantinedChanges = {
                queryCount++
                if (queryCount == 1) listOf(sampleQuarantined(7L, "Alice")) else emptyList()
            },
            discardQuarantinedChange = { discarded += it },
            scope = TestScope(dispatcher),
            workDispatcher = dispatcher
        )
        advanceUntilIdle()
        vm.showQuarantinedChangesDialog()
        assertTrue(vm.quarantinedDialogOpen.value)

        vm.discardQuarantined(7L)
        advanceUntilIdle()

        assertEquals(listOf(7L), discarded)
        assertTrue(vm.quarantinedChanges.value.isEmpty())
        assertFalse(vm.quarantinedDialogOpen.value)
    }

    private fun sampleQuarantined(outboxId: Long, name: String?) = QuarantinedChange(
        outboxId = outboxId,
        displayName = name,
        operation = QuarantinedOperation.UPDATE,
        reason = "HttpException: 422"
    )

    @Test fun outbox_stats_default_on_query_failure() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val vm = SettingsViewModel(
            syncNow = { error("not used") },
            signOut = { error("not used") },
            queryOutboxStats = { error("db error") },
            scope = TestScope(dispatcher),
            workDispatcher = dispatcher
        )
        advanceUntilIdle()
        assertEquals(OutboxStats(0, 0), vm.outboxStats.value)
    }
}
