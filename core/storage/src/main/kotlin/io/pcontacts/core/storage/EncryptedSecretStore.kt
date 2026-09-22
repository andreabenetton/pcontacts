// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.storage

import android.content.Context
import android.content.SharedPreferences
import io.pcontacts.core.logging.Logger
import io.pcontacts.core.logging.NoOpSink
import io.pcontacts.core.logging.RedactingLogger
import java.io.File
import java.security.GeneralSecurityException
import java.util.Base64

/**
 * Production `SecretStore` (ADR-0009, amended 2026-09-22): every value
 * is sealed by the Keystore AES-256-GCM key `pcontacts.kekv1`
 * ([KeystoreAesGcmKek]) and stored as base64 in a private
 * SharedPreferences file. Nothing else is in that file, so logout can
 * clear it wholesale. Key names are compile-time constants, so their
 * being readable reveals nothing.
 *
 * Durability: every write and the logout wipe use `commit()`, and a
 * refused commit throws [SecretStoreWriteException] — a sign-out must
 * never be reported when the secrets may still be on disk. Reads fail
 * closed: ciphertext that no longer opens (the key was deleted, the
 * blob is damaged) reads as absent, which sends the app to the
 * existing "sign in again" path instead of crashing a sync.
 *
 * The earlier EncryptedSharedPreferences file and its androidx master
 * key are deleted on first start after the update; users of 1.x sign
 * in once more. Nothing is migrated: the old file also held Tink
 * keysets, and deleting it is the only wipe that removes those.
 */
class EncryptedSecretStore internal constructor(
    private val prefs: SharedPreferences,
    private val cipher: SecretCipher,
    private val logger: Logger
) : SecretStore {

    override fun uid(): String? = readString(KEY_UID)
    override fun setUid(value: String?) = write(KEY_UID, value?.encodeToByteArray())

    override fun accessToken(): String? = readString(KEY_ACCESS_TOKEN)
    override fun setAccessToken(value: String?) = write(KEY_ACCESS_TOKEN, value?.encodeToByteArray())

    override fun refreshToken(): String? = readString(KEY_REFRESH_TOKEN)
    override fun setRefreshToken(value: String?) = write(KEY_REFRESH_TOKEN, value?.encodeToByteArray())

    override fun setTokens(accessToken: String?, refreshToken: String?) {
        val editor = prefs.edit()
        editor.put(KEY_ACCESS_TOKEN, accessToken?.encodeToByteArray())
        editor.put(KEY_REFRESH_TOKEN, refreshToken?.encodeToByteArray())
        if (!editor.commit()) throw SecretStoreWriteException(KEY_ACCESS_TOKEN)
    }

    override fun keyPassword(): ByteArray? = read(KEY_PASSWORD)
    override fun setKeyPassword(value: ByteArray?) = write(KEY_PASSWORD, value)

    override fun humanVerificationToken(): String? = readString(KEY_HV_TOKEN)
    override fun setHumanVerificationToken(value: String?) = write(KEY_HV_TOKEN, value?.encodeToByteArray())

    override fun humanVerificationTokenType(): String? = readString(KEY_HV_TOKEN_TYPE)
    override fun setHumanVerificationTokenType(value: String?) = write(KEY_HV_TOKEN_TYPE, value?.encodeToByteArray())

    /** Values first, then the key: a Keystore failure after the commit still leaves nothing readable. */
    override fun logout() {
        if (!prefs.edit().clear().commit()) throw SecretStoreWriteException("logout")
        cipher.delete()
    }

    private fun readString(key: String): String? = read(key)?.decodeToString()

    private fun read(key: String): ByteArray? {
        val encoded = prefs.getString(key, null) ?: return null
        return try {
            cipher.unwrap(Base64.getDecoder().decode(encoded))
        } catch (e: GeneralSecurityException) {
            logger.warn(e) { "secret '$key' unreadable; treating as absent" }
            null
        } catch (e: IllegalArgumentException) {
            logger.warn(e) { "secret '$key' malformed; treating as absent" }
            null
        }
    }

    private fun write(key: String, value: ByteArray?) {
        val editor = prefs.edit()
        editor.put(key, value)
        if (!editor.commit()) throw SecretStoreWriteException(key)
    }

    private fun SharedPreferences.Editor.put(key: String, value: ByteArray?) {
        if (value == null) {
            remove(key)
        } else {
            putString(key, Base64.getEncoder().encodeToString(cipher.wrap(value)))
        }
    }

    companion object {
        internal const val FILE_NAME: String = "pcontacts_auth_v2"
        private const val KEY_UID: String = "uid"
        private const val KEY_ACCESS_TOKEN: String = "access_token"
        private const val KEY_REFRESH_TOKEN: String = "refresh_token"
        private const val KEY_PASSWORD: String = "key_password"
        private const val KEY_HV_TOKEN: String = "hv_token"
        private const val KEY_HV_TOKEN_TYPE: String = "hv_token_type"

        /** The EncryptedSharedPreferences file of releases up to 1.7.2 and its androidx master-key alias. */
        internal const val LEGACY_FILE_NAME: String = "pcontacts_auth_prefs"
        private const val LEGACY_MASTER_KEY_ALIAS: String = "_androidx_security_master_key_"

        fun create(
            context: Context,
            logger: Logger = RedactingLogger(tag = "SecretStore", sink = NoOpSink)
        ): EncryptedSecretStore {
            val app = context.applicationContext
            if (purgeLegacy(app, KeystoreAesGcmKek(LEGACY_MASTER_KEY_ALIAS), logger)) {
                SharedPreferencesUserPreferences(app).secretsStorageUpgraded = true
            }
            return EncryptedSecretStore(
                app.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE),
                KeystoreAesGcmKek(),
                logger
            )
        }

        /**
         * Deletes the pre-2.0 secret file and its master key, once. A
         * failure here leaves the old ciphertext in place; it is never
         * read, so the outcome is the same as success: sign in again.
         * Returns `true` when a legacy file was found, i.e. this install
         * was upgraded from 1.x and the user must sign in again.
         */
        internal fun purgeLegacy(app: Context, legacyMasterKey: SecretCipher, logger: Logger): Boolean {
            val legacyFile = File(app.applicationInfo.dataDir, "shared_prefs/$LEGACY_FILE_NAME.xml")
            if (!legacyFile.exists()) return false
            try {
                app.deleteSharedPreferences(LEGACY_FILE_NAME)
                legacyMasterKey.delete()
                logger.info { "legacy secret store purged; sign-in required" }
            } catch (e: GeneralSecurityException) {
                logger.warn(e) { "legacy secret store purge failed" }
            } catch (e: IllegalStateException) {
                logger.warn(e) { "legacy secret store purge failed" }
            }
            return true
        }
    }
}
