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
 *   Idle ─(login)→ Submitting ─→ Success | Failed
 *                              ├→ TwoFactorRequired
 *                              └→ HumanVerificationRequired
 *
 *   HumanVerificationRequired ─(retryAfterVerification)→ Submitting
 *       └→ Success | Failed | TwoFactorRequired | HumanVerificationRequired
 *
 *   TwoFactorRequired ─(submitTwoFactor)→ TwoFactorSubmitting
 *       └→ Success | TwoFactorFailed
 *
 *   TwoFactorFailed ─(submitTwoFactor)→ TwoFactorSubmitting   // retry path
 */
sealed interface LoginUiState {
    data object Idle : LoginUiState
    data object Submitting : LoginUiState
    data class Success(val uid: String, val username: String) : LoginUiState
    data class TwoFactorRequired(val uid: String, val username: String) : LoginUiState
    data class TwoFactorSubmitting(val uid: String, val username: String) : LoginUiState
    data class TwoFactorFailed(val uid: String, val username: String, val reason: String) : LoginUiState

    /**
     * Proton demanded a captcha (Code 9001) at `/auth` before issuing
     * the 2FA challenge. The hosting Activity should launch the captcha
     * URL in a Custom Tab and call `retryAfterVerification()` when the
     * user returns. [verificationUrl] is null when the 9001 body did
     * not include a captcha Details block — fall back to a "verify on
     * the web" dialog.
     */
    data class HumanVerificationRequired(val verificationUrl: String?) : LoginUiState

    data class Failed(val reason: String) : LoginUiState
}
