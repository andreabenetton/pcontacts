// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.feature.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsScreenTest {

    @get:Rule val composeRule = createComposeRule()

    private fun viewModel(
        syncNow: suspend () -> SettingsActionResult = { SettingsActionResult.Success("Sync requested") },
        signOut: suspend () -> SettingsActionResult = { SettingsActionResult.Success() },
        queryVerificationStats: suspend () -> VerificationStats? = { null },
        queryOutboxStats: suspend () -> OutboxStats = { OutboxStats(0, 0) },
        queryPendingDeletes: suspend () -> List<PendingDelete> = { emptyList() },
        queryConflicts: suspend () -> List<ConflictInfo> = { emptyList() },
        cancelDelete: suspend (String) -> Unit = {},
        resolveConflict: suspend (String, ConflictResolution) -> Unit = { _, _ -> },
    ): SettingsViewModel {
        val dispatcher = UnconfinedTestDispatcher()
        return SettingsViewModel(
            syncNow = syncNow,
            signOut = signOut,
            queryVerificationStats = queryVerificationStats,
            queryOutboxStats = queryOutboxStats,
            queryPendingDeletes = queryPendingDeletes,
            queryConflicts = queryConflicts,
            cancelDelete = cancelDelete,
            resolveConflict = resolveConflict,
            scope = TestScope(dispatcher),
            workDispatcher = dispatcher
        )
    }

    @Test
    fun initial_idle_state_shows_title_and_buttons() {
        val vm = viewModel()
        composeRule.setContent {
            SettingsScreen(vm, onSignedOut = {})
        }
        composeRule.onNodeWithText("Proton Contacts").assertIsDisplayed()
        composeRule.onNodeWithText("Sync now").assertIsDisplayed()
        composeRule.onNodeWithText("Sign out").assertIsDisplayed()
    }

    @Test
    fun sync_now_shows_progress_then_done() {
        val gate = CompletableDeferred<SettingsActionResult>()
        val vm = viewModel(syncNow = { gate.await() })

        composeRule.setContent {
            SettingsScreen(vm, onSignedOut = {})
        }
        composeRule.onNodeWithText("Sync now").performClick()
        composeRule.waitForIdle()

        // While syncing, button should be disabled
        composeRule.onNodeWithText("Sync now").assertIsNotEnabled()

        gate.complete(SettingsActionResult.Success("Sync requested"))
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Sync requested").assertIsDisplayed()
    }

    @Test
    fun sync_now_failure_shows_error() {
        val vm = viewModel(
            syncNow = { SettingsActionResult.Failure("no_account") }
        )

        composeRule.setContent {
            SettingsScreen(vm, onSignedOut = {})
        }
        composeRule.onNodeWithText("Sync now").performClick()
        composeRule.waitForIdle()

        composeRule.onNode(hasText("no_account", substring = true))
            .assertIsDisplayed()
    }

    @Test
    fun sign_out_shows_progress_then_calls_callback() {
        val gate = CompletableDeferred<SettingsActionResult>()
        var signedOutCalled = false
        val vm = viewModel(signOut = { gate.await() })

        composeRule.setContent {
            SettingsScreen(vm, onSignedOut = { signedOutCalled = true })
        }
        composeRule.onNodeWithText("Sign out").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Sign out").assertIsNotEnabled()

        gate.complete(SettingsActionResult.Success())
        composeRule.waitForIdle()

        assertTrue(signedOutCalled)
    }

    @Test
    fun sign_out_failure_shows_error() {
        val vm = viewModel(
            signOut = { SettingsActionResult.Failure("missing_contacts_permission") }
        )

        composeRule.setContent {
            SettingsScreen(vm, onSignedOut = {})
        }
        composeRule.onNodeWithText("Sign out").performClick()
        composeRule.waitForIdle()

        composeRule.onNode(hasText("missing_contacts_permission", substring = true))
            .assertIsDisplayed()
    }

    @Test
    fun outbox_banner_shows_pending_count() {
        val vm = viewModel(
            queryOutboxStats = { OutboxStats(pending = 3, quarantined = 0) }
        )

        composeRule.setContent {
            SettingsScreen(vm, onSignedOut = {})
        }
        composeRule.waitForIdle()

        composeRule.onNode(hasText("3 changes pending sync", substring = true))
            .assertIsDisplayed()
    }

    @Test
    fun outbox_banner_shows_quarantined_count() {
        val vm = viewModel(
            queryOutboxStats = { OutboxStats(pending = 0, quarantined = 2) }
        )

        composeRule.setContent {
            SettingsScreen(vm, onSignedOut = {})
        }
        composeRule.waitForIdle()

        composeRule.onNode(hasText("2 changes failed permanently", substring = true))
            .assertIsDisplayed()
    }

    @Test
    fun pending_delete_banner_shows_with_cancel() {
        val vm = viewModel(
            queryPendingDeletes = { listOf(PendingDelete("ct-abc123def456", 1000L)) }
        )

        composeRule.setContent {
            SettingsScreen(vm, onSignedOut = {})
        }
        composeRule.waitForIdle()

        composeRule.onNode(hasText("1 contact scheduled for deletion", substring = true))
            .assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").assertIsDisplayed()
    }

    @Test
    fun cancel_pending_delete_calls_seam() {
        val cancelled = mutableListOf<String>()
        val vm = viewModel(
            queryPendingDeletes = { listOf(PendingDelete("ct-abc123def456", 1000L)) },
            cancelDelete = { cancelled += it }
        )

        composeRule.setContent {
            SettingsScreen(vm, onSignedOut = {})
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.waitForIdle()

        assertEquals(listOf("ct-abc123def456"), cancelled)
    }

    @Test
    fun conflict_banner_shows_with_resolve_button() {
        val vm = viewModel(
            queryConflicts = { listOf(ConflictInfo("ct-1", "Alice", "fullName")) }
        )

        composeRule.setContent {
            SettingsScreen(vm, onSignedOut = {})
        }
        composeRule.waitForIdle()

        composeRule.onNode(hasText("1 contact with sync conflicts", substring = true))
            .assertIsDisplayed()
        composeRule.onNodeWithText("Resolve").assertIsDisplayed()
    }

    @Test
    fun conflict_resolution_dialog_shows_options() {
        val vm = viewModel(
            queryConflicts = { listOf(ConflictInfo("ct-1", "Alice", "fullName")) }
        )

        composeRule.setContent {
            SettingsScreen(vm, onSignedOut = {})
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Resolve").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Resolve conflict").assertIsDisplayed()
        composeRule.onNodeWithText("Use phone version").assertIsDisplayed()
        composeRule.onNodeWithText("Use Proton version").assertIsDisplayed()
    }

    @Test
    fun resolve_conflict_use_local_calls_seam() {
        val resolved = mutableListOf<Pair<String, ConflictResolution>>()
        val vm = viewModel(
            queryConflicts = { listOf(ConflictInfo("ct-1", "Alice", "fullName")) },
            resolveConflict = { id, res -> resolved += id to res }
        )

        composeRule.setContent {
            SettingsScreen(vm, onSignedOut = {})
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Resolve").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Use phone version").performClick()
        composeRule.waitForIdle()

        assertEquals(listOf("ct-1" to ConflictResolution.USE_LOCAL), resolved)
    }

    @Test
    fun verification_warning_shows_when_unverified_contacts_exist() {
        val vm = viewModel(
            queryVerificationStats = { VerificationStats(totalContacts = 10, unverifiedContacts = 3) }
        )

        composeRule.setContent {
            SettingsScreen(vm, onSignedOut = {})
        }
        composeRule.waitForIdle()

        composeRule.onNode(hasText("3 of 10 contacts could not be verified", substring = true))
            .assertIsDisplayed()
    }
}
