// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.sync.auth

/**
 * Outcome of an SRP login attempt. The UI maps each variant to a
 * distinct surface: `Success` lands on the main screen, `TwoFactorRequired`
 * routes to the TOTP entry screen, `Failed` shows an error.
 *
 * `Failed.reason` is a short, non-sensitive string — sensitive details
 * stay in :core:logging's RedactingLogger (ADR-0015).
 */
sealed interface LoginResult {
    val uid: String?
    val username: String?

    data class Success(override val uid: String, override val username: String) : LoginResult
    data class TwoFactorRequired(override val uid: String, override val username: String) : LoginResult

    /**
     * Proton returned Code:9001 on `/auth/info`, `/auth`, `/auth/2fa` or
     * a key-derivation call. The user solves the captcha in the in-app
     * WebView (ADR-0019); the token it stores makes every following
     * request carry the `x-pm-human-verification-token{,-type}` headers.
     * The caller then re-invokes `login(...)` (credentials phase) or
     * `submitTwoFactorCode(...)` with a fresh code (2FA phase — the SRP
     * session is kept).
     *
     * [verificationUrl] is null when the 9001 body did not include the
     * captcha Details block — the UI falls back to a "verify on the web"
     * dialog instead of opening the WebView.
     */
    data class HumanVerificationRequired(
        val verificationUrl: String?,
        override val uid: String? = null,
        override val username: String? = null
    ) : LoginResult

    data class Failed(
        val reason: String,
        override val uid: String? = null,
        override val username: String? = null
    ) : LoginResult
}
