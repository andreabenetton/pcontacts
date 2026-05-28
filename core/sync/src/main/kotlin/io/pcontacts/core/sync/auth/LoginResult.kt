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
     * Proton returned Code:9001 on `/auth`. The user must complete a
     * captcha (or recovery-email/SMS challenge) before SRP can succeed.
     * After verification, the caller re-invokes `login(...)` with the
     * same credentials; the next `/auth` carries the
     * `x-pm-human-verification-token` headers and is expected to pass.
     *
     * [verificationUrl] is null when the 9001 body did not include the
     * captcha Details block — UI falls back to a "verify on the web"
     * dialog instead of opening a Custom Tab.
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
