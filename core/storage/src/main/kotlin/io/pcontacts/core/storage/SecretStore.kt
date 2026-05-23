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
 *     `SharedPreferences` and the Android Keystore. A detekt rule will
 *     fail any other module that constructs a SharedPreferences directly
 *     (rule added once detekt is wired in a follow-up commit).
 *   - The mailbox key password is wrapped under a Keystore-backed
 *     AES-256-GCM key (`pcontacts.kekv1`) before it touches
 *     EncryptedSharedPreferences. The wrap key is rotated on full
 *     re-auth and deleted on logout.
 *   - On `logout()` every field is wiped and the Keystore alias is
 *     deleted. This is the single line of defence against a stolen
 *     device with an unlocked app sandbox.
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
     * Wipes every secret and deletes the Keystore AEAD key. Subsequent
     * reads return null. Called from the sign-out flow and during the
     * "Forget account" path.
     */
    fun logout()
}
