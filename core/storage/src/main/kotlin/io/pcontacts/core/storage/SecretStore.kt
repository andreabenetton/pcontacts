// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.storage

/**
 * Single read/write surface for every sensitive value the app holds:
 * Proton session UID, AccessToken, RefreshToken, and the mailbox key
 * password derived via bcrypt-SHA-512.
 *
 * Per ADR-0009:
 *   - This is the ONLY place in the codebase that touches
 *     `SharedPreferences` and the Android Keystore. Direct
 *     `SharedPreferences` constructor calls outside `:core:storage`
 *     are forbidden (CLAUDE.md).
 *   - Every value is sealed under the Keystore-backed AES-256-GCM key
 *     `pcontacts.kekv1` before it is stored in a private preferences
 *     file. The key is created at first sign-in and deleted at logout;
 *     nothing rotates it.
 *   - `logout()` is the durable wipe: the values are committed away
 *     synchronously and the Keystore alias is deleted and verified.
 *     It throws when either step fails so the caller never reports a
 *     sign-out that did not happen.
 */
interface SecretStore {

    fun uid(): String?
    fun setUid(value: String?)

    fun accessToken(): String?
    fun setAccessToken(value: String?)

    fun refreshToken(): String?
    fun setRefreshToken(value: String?)

    /** Returns the unwrapped keyPassword bytes, or null if not stored. */
    fun keyPassword(): ByteArray?

    /** Wraps and stores the keyPassword bytes under the Keystore AEAD key. */
    fun setKeyPassword(value: ByteArray?)

    /**
     * Human-verification token (`x-pm-human-verification-token`) and its
     * type (`x-pm-human-verification-token-type`, e.g. `"captcha"`).
     * Stored after the user completes a 9001 challenge so subsequent
     * requests can attach the two headers transparently
     * (see HumanVerificationHeadersInterceptor in :core:proton-api).
     *
     * Same threat profile as `accessToken` — session-scoped opaque
     * value, no KEK wrap. Cleared by `logout()` and by the interceptor
     * on a subsequent 9001 (stale-token detection).
     */
    fun humanVerificationToken(): String?
    fun setHumanVerificationToken(value: String?)
    fun humanVerificationTokenType(): String?
    fun setHumanVerificationTokenType(value: String?)

    /**
     * Wipes every secret durably and deletes the Keystore AEAD key.
     * Subsequent reads return null. Throws [SecretStoreWriteException]
     * (or a Keystore error) when the wipe could not be completed; the
     * sign-out flow then keeps the account and reports the failure.
     */
    fun logout()
}

/** A `commit()` of the secrets file was refused; the value may still be on disk. */
class SecretStoreWriteException(key: String) : IllegalStateException("secret store commit failed for '$key'")
