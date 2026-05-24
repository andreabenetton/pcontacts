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

data class ConflictInfo(
    val protonContactId: String,
    val displayName: String?,
    val conflictFields: String?
)

enum class ConflictResolution { USE_LOCAL, USE_SERVER }
