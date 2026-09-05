// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.feature.settings

/**
 * Settings screen state. The screen has two actions (Sync Now /
 * Sign Out); the state machine reflects whichever is currently
 * in-flight (or the failure of the most recent one). [Syncing] covers
 * only the requestSync round-trip — the actual SyncAdapter run is
 * tracked by [SettingsViewModel.syncRunning], and its outcome by
 * [SettingsViewModel.lastSync].
 */
sealed interface SettingsUiState {
    data object Idle : SettingsUiState
    data object Syncing : SettingsUiState
    data class SyncFailed(val reason: String) : SettingsUiState

    data object SigningOut : SettingsUiState
    data object SignedOut : SettingsUiState
    data class SignOutFailed(val reason: String) : SettingsUiState
}

/**
 * Result of the most recent completed sync run, as persisted by the
 * sync adapter. It is overwritten only when a run finishes, so the UI
 * keeps showing the previous run's outcome for the whole duration of
 * the next one. `failureMessage` is pre-localized upstream (in `:app`,
 * which owns the error-code mapping); null means the run succeeded.
 */
data class LastSyncSummary(
    val syncedAtMillis: Long?,
    val failureMessage: String? = null,
    val failedContacts: Int = 0
)

data class OutboxStats(
    val pending: Int,
    val quarantined: Int
)

data class PendingDelete(
    val protonContactId: String,
    val createdAt: Long
)

/** Which outbound operation a quarantined change was carrying. */
enum class QuarantinedOperation { CREATE, UPDATE, DELETE, UNKNOWN }

/**
 * One row for the failed-changes dialog. Like
 * [UnverifiedContactSummary], `displayName` is resolved upstream (in
 * `:app`) via ContentResolver; it is null when the local contact can no
 * longer be located — typically a deletion whose row is already gone.
 *
 * `reason` is the persisted quarantine reason (an exception class name
 * plus HTTP code, or a short internal reason); it never carries
 * decrypted contact content.
 */
data class QuarantinedChange(
    val outboxId: Long,
    val displayName: String?,
    val operation: QuarantinedOperation,
    val reason: String?
)

data class ConflictInfo(
    val protonContactId: String,
    val displayName: String?,
    val conflictFields: String?
)

enum class ConflictResolution { USE_LOCAL, USE_SERVER }

data class ContactsAccessApp(
    val appName: String,
    val packageName: String
)
