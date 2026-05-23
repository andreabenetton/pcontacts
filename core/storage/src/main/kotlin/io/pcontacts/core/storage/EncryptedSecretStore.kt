// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.Base64

/**
 * Production `SecretStore` (ADR-0009).
 *   - Tokens (UID, AccessToken, RefreshToken) land in
 *     EncryptedSharedPreferences directly. The MasterKey is StrongBox-backed
 *     where the device supports it.
 *   - The mailbox keyPassword is double-wrapped: KeystoreAesGcmKek
 *     (alias `pcontacts.kekv1`) encrypts the bytes; the resulting blob is
 *     base64-encoded and stored alongside the tokens. `logout()` deletes
 *     both the prefs file's entries and the KEK alias.
 *
 * Construction is intentionally a single side-effect-free factory call so
 * the encrypted-prefs initialization (which touches Keystore) runs at a
 * predictable point in app lifecycle, not lazily inside the orchestrator.
 */
class EncryptedSecretStore private constructor(
    private val prefs: SharedPreferences,
    private val kek: KeystoreAesGcmKek
) : SecretStore {

    override fun uid(): String? = prefs.getString(KEY_UID, null)
    override fun setUid(value: String?) = prefs.put(KEY_UID, value)

    override fun accessToken(): String? = prefs.getString(KEY_ACCESS_TOKEN, null)
    override fun setAccessToken(value: String?) = prefs.put(KEY_ACCESS_TOKEN, value)

    override fun refreshToken(): String? = prefs.getString(KEY_REFRESH_TOKEN, null)
    override fun setRefreshToken(value: String?) = prefs.put(KEY_REFRESH_TOKEN, value)

    override fun keyPassword(): ByteArray? {
        val wrappedB64 = prefs.getString(KEY_PASSWORD_WRAPPED, null) ?: return null
        return kek.unwrap(Base64.getDecoder().decode(wrappedB64))
    }

    override fun setKeyPassword(value: ByteArray?) {
        if (value == null) {
            prefs.put(KEY_PASSWORD_WRAPPED, null)
            return
        }
        val wrapped = kek.wrap(value)
        prefs.put(KEY_PASSWORD_WRAPPED, Base64.getEncoder().encodeToString(wrapped))
    }

    override fun logout() {
        prefs.edit()
            .remove(KEY_UID)
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_PASSWORD_WRAPPED)
            .apply()
        kek.delete()
    }

    private fun SharedPreferences.put(key: String, value: String?) {
        edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }

    companion object {
        private const val FILE_NAME: String = "pcontacts_auth_prefs"
        private const val KEY_UID: String = "uid"
        private const val KEY_ACCESS_TOKEN: String = "access_token"
        private const val KEY_REFRESH_TOKEN: String = "refresh_token"
        private const val KEY_PASSWORD_WRAPPED: String = "key_password_wrapped"

        fun create(context: Context): EncryptedSecretStore {
            val masterKey = MasterKey.Builder(context.applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .setRequestStrongBoxBacked(true)
                .build()
            val prefs = EncryptedSharedPreferences.create(
                context.applicationContext,
                FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            return EncryptedSecretStore(prefs, KeystoreAesGcmKek())
        }
    }
}
