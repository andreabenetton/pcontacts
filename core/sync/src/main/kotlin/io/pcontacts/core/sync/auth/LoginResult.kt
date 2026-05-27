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
    data class Failed(val reason: String, override val uid: String? = null, override val username: String? = null) : LoginResult
}
