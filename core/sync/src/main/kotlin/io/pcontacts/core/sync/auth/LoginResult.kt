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

    /** Where a 9001 interrupted the login; the caller resumes exactly there. */
    enum class HvStage {
        /** Before or during `/auth`: re-run `login(...)` with the credentials. */
        CREDENTIALS,

        /** On `/auth/2fa`: the SRP session is kept; submit a fresh code. */
        TWO_FACTOR,

        /** After the code was accepted, on `/users` or `/keys/salts`: call `retryKeyDerivation()`; no new code. */
        KEY_DERIVATION
    }

    data class Success(override val uid: String, override val username: String) : LoginResult
    data class TwoFactorRequired(override val uid: String, override val username: String) : LoginResult

    /**
     * Proton returned Code:9001 on `/auth/info`, `/auth`, `/auth/2fa` or
     * a key-derivation call. The user solves the captcha in the in-app
     * WebView (ADR-0019); the token it stores makes every following
     * request carry the `x-pm-human-verification-token{,-type}` headers.
     * [stage] says where it happened and therefore how to resume: the
     * credentials phase re-runs `login(...)`, the 2FA phase submits a
     * fresh code on the kept session, the key-derivation phase (the code
     * was already accepted) calls `retryKeyDerivation()` — asking for
     * another code there would be wrong, Proton already has it.
     *
     * [verificationUrl] is null when the 9001 body did not include the
     * captcha Details block — the UI falls back to a "verify on the web"
     * dialog instead of opening the WebView.
     */
    data class HumanVerificationRequired(
        val verificationUrl: String?,
        override val uid: String? = null,
        override val username: String? = null,
        val stage: HvStage = HvStage.CREDENTIALS
    ) : LoginResult

    data class Failed(
        val reason: String,
        override val uid: String? = null,
        override val username: String? = null
    ) : LoginResult
}
