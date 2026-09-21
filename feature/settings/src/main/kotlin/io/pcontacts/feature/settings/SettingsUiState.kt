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

/**
 * Navigation the host (`:app`) performs for the screen. Intents that
 * may not resolve on every device are nullable: a null action hides
 * its button rather than showing one that fails.
 */
data class SettingsActions(
    val onSignedOut: () -> Unit,
    val onPickContact: () -> Unit = {},
    val onOpenContactsPermission: () -> Unit = {},
    val contactsPermissionRoute: ContactsPermissionRoute = ContactsPermissionRoute.DIRECT,
    val onOpenContactsStorage: (() -> Unit)? = null
)

/**
 * How far [SettingsActions.onOpenContactsPermission] can get: the
 * Contacts permission page itself, the system Permission manager one
 * tap away, or only the privacy hub. Anything short of DIRECT shows
 * the user the remaining taps next to the button.
 */
enum class ContactsPermissionRoute { DIRECT, PERMISSION_MANAGER, PRIVACY_SETTINGS }
