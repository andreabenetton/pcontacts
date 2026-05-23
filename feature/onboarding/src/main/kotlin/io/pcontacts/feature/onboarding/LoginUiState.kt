// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.feature.onboarding

/**
 * UI projection of `io.pcontacts.core.sync.auth.LoginResult` plus the
 * intermediate states the screens need to show progress.
 *
 * Kept as a sealed interface so `when` is exhaustive in the Composables.
 *
 * The state machine is:
 *
 *   Idle ─(login)→ Submitting ─→ Success | Failed | TwoFactorRequired
 *
 *   TwoFactorRequired ─(submitTwoFactor)→ TwoFactorSubmitting
 *       └→ Success | TwoFactorFailed
 *
 *   TwoFactorFailed ─(submitTwoFactor)→ TwoFactorSubmitting   // retry path
 */
sealed interface LoginUiState {
    data object Idle : LoginUiState
    data object Submitting : LoginUiState
    data class Success(val uid: String) : LoginUiState
    data class TwoFactorRequired(val uid: String) : LoginUiState
    data class TwoFactorSubmitting(val uid: String) : LoginUiState
    data class TwoFactorFailed(val uid: String, val reason: String) : LoginUiState
    data class Failed(val reason: String) : LoginUiState
}
