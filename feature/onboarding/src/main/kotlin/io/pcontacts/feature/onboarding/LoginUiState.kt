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
 *       └→ Success | TwoFactorFailed | TwoFactorHumanVerificationRequired
 *
 *   TwoFactorHumanVerificationRequired ─(retryAfterVerification)→ TwoFactorRequired   // fresh code, same session
 *       └─(a second 9001 on the resubmit)→ TwoFactorFailed("verification_rejected")   // fail closed
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
     * the 2FA challenge. The hosting Activity opens the in-app
     * verification WebView (ADR-0019) and calls `retryAfterVerification()`
     * on RESULT_OK. [verificationUrl] is null when the 9001 body did
     * not include a captcha Details block — fall back to a "verify on
     * the web" dialog.
     */
    data class HumanVerificationRequired(val verificationUrl: String?) : LoginUiState

    /**
     * The same demand, raised on `/auth/2fa`: the SRP session is
     * established and kept. The host opens the same WebView; on
     * RESULT_OK `retryAfterVerification()` returns to [TwoFactorRequired]
     * so the user enters a fresh code rather than re-sending an aged one.
     */
    data class TwoFactorHumanVerificationRequired(
        val uid: String,
        val username: String,
        val verificationUrl: String?
    ) : LoginUiState

    /**
     * The demand raised after `/auth/2fa` accepted the code, on the
     * key-derivation calls. The code is spent; on RESULT_OK
     * `retryAfterVerification()` resumes key derivation on the kept
     * session — it never asks for another code.
     */
    data class KeyDerivationHumanVerificationRequired(
        val uid: String,
        val username: String,
        val verificationUrl: String?
    ) : LoginUiState

    data class Failed(val reason: String) : LoginUiState
}
