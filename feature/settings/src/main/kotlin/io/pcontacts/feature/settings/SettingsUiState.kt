// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.feature.settings

import android.graphics.Bitmap

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
    val failedContacts: Int = 0,
    /** When the last run finished, converged or not; [syncedAtMillis] is the last converged one. */
    val lastRunAtMillis: Long? = null
)

/**
 * The one vocabulary for sync state across the app: a spinner means a
 * sync is running, a check means it went through, a warning means
 * something needs attention, an info mark is neutral. Every surface
 * that reports on a sync — the status card, an imported row, the bulk
 * progress — draws it through [SyncIndicator], so the glyph always
 * means the same thing wherever it appears.
 */
enum class SyncTone { RUNNING, OK, WARN, INFO }

/**
 * The run in flight: the [stage] it is in (null when unknown) and that
 * stage's items done so far out of its total (0 for a stage without a count).
 */
data class SyncProgress(val done: Int, val total: Int, val stage: SyncStage? = null)

/** The stages a sync run goes through, in order; each one the card names. */
enum class SyncStage {
    /** Local changes going to Proton, counted. */
    SENDING,

    /** Proton's contact list compared with the phone's. */
    CHECKING,

    /** The contacts that changed on Proton being fetched, counted. */
    DOWNLOADING,

    /** The result being written into the phone's contacts. */
    SAVING
}

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
 * decrypted contact content. `rawContactId` lets the row open the
 * contact in the system app; null when the change no longer maps to a
 * local row.
 */
data class QuarantinedChange(
    val outboxId: Long,
    val displayName: String?,
    val operation: QuarantinedOperation,
    val reason: String?,
    val rawContactId: Long? = null
)

data class ConflictInfo(
    val protonContactId: String,
    val displayName: String?,
    val conflictFields: String?,
    /** The Proton copy was deleted while the phone had a change to it (ADR-0017 §3C). */
    val serverDeleted: Boolean = false
)

enum class ConflictResolution { USE_LOCAL, USE_SERVER }

/** `icon` is the launcher icon rasterised by the host; null when it could not be loaded. */
data class ContactsAccessApp(
    val appName: String,
    val packageName: String,
    val icon: Bitmap? = null
)

/** Which READ_CONTACTS list a banner opens. */
enum class ContactsAccessKind { USER, SYSTEM }

/**
 * Navigation the host (`:app`) performs for the screen. Intents that
 * may not resolve on every device are nullable: a null action hides
 * its button rather than showing one that fails.
 */
data class SettingsActions(
    val onSignedOut: () -> Unit,
    val onOpenLinkedImport: () -> Unit = {},
    val onOpenContactsAccess: (ContactsAccessKind) -> Unit = {},
    val onOpenContactsStorage: (() -> Unit)? = null,
    val onOpenDeGoogledRoms: () -> Unit = {},
    val onOpenDependencies: () -> Unit = {},
    /** The source repository, opened in the browser from the GitHub mark in the app bar. */
    val onOpenRepository: () -> Unit = {},
    /** Android's sync settings page, for the phone-wide "Auto-sync data" switch the app does not flip itself. */
    val onOpenSyncSettings: () -> Unit = {}
)

/**
 * How far the "manage permission" action of [ContactsAccessScreen] can
 * get: the Contacts permission page itself, the system Permission
 * manager one tap away, or only the privacy hub. Anything short of
 * DIRECT shows the user the remaining taps next to the button.
 */
enum class ContactsPermissionRoute { DIRECT, PERMISSION_MANAGER, PRIVACY_SETTINGS }
