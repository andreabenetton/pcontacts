// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.feature.settings

/**
 * Settings screen state. The screen has two actions (Sync Now /
 * Sign Out); the state machine reflects whichever is currently
 * in-flight (or the result of the most recent one).
 */
sealed interface SettingsUiState {
    data object Idle : SettingsUiState
    data object Syncing : SettingsUiState
    data class SyncDone(val message: String) : SettingsUiState
    data class SyncFailed(val reason: String) : SettingsUiState

    data object SigningOut : SettingsUiState
    data object SignedOut : SettingsUiState
    data class SignOutFailed(val reason: String) : SettingsUiState
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
