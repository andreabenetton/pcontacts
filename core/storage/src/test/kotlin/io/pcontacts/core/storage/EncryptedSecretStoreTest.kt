// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.storage

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import io.pcontacts.core.logging.NoOpSink
import io.pcontacts.core.logging.RedactingLogger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The store's logic against a plain preferences file and a JVM AES-GCM
 * cipher with the same wire format as the Keystore one, which
 * Robolectric cannot provide.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class EncryptedSecretStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs: SharedPreferences = context.getSharedPreferences("test_auth", Context.MODE_PRIVATE)
    private val cipher = JvmAesGcmCipher()
    private val logger = RedactingLogger(tag = "test", sink = NoOpSink)

    private fun store(cipher: SecretCipher = this.cipher) = EncryptedSecretStore(prefs, cipher, logger)

    @Test fun string_secrets_round_trip_and_are_not_stored_in_plaintext() {
        val s = store()
        s.setUid("uid-1")
        s.setAccessToken("access")
        s.setRefreshToken("refresh")
        s.setHumanVerificationToken("hv")
        s.setHumanVerificationTokenType("captcha")

        assertEquals("uid-1", s.uid())
        assertEquals("access", s.accessToken())
        assertEquals("refresh", s.refreshToken())
        assertEquals("hv", s.humanVerificationToken())
        assertEquals("captcha", s.humanVerificationTokenType())
        val raw = prefs.getString("access_token", null)!!
        assertNotEquals("access", raw)
        assertTrue(Base64.getDecoder().decode(raw).size > "access".length)
    }

    @Test fun setTokens_writes_both_tokens_together() {
        val s = store()

        s.setTokens("access-2", "refresh-2")

        assertEquals("access-2", s.accessToken())
        assertEquals("refresh-2", s.refreshToken())
        s.setTokens(null, null)
        assertNull(s.accessToken())
        assertNull(s.refreshToken())
    }

    @Test fun keyPassword_round_trips_bytes() {
        val s = store()
        val bytes = byteArrayOf(1, 2, 3, 4)

        s.setKeyPassword(bytes)

        assertArrayEquals(bytes, s.keyPassword())
    }

    @Test fun set_null_removes_the_key() {
        val s = store()
        s.setUid("uid-1")

        s.setUid(null)

        assertNull(s.uid())
        assertFalse(prefs.contains("uid"))
    }

    @Test fun logout_clears_every_key_and_deletes_the_kek() {
        val s = store()
        s.setUid("uid-1")
        s.setKeyPassword(byteArrayOf(7))

        s.logout()

        assertTrue(prefs.all.isEmpty())
        assertTrue(cipher.deleted)
    }

    @Test fun logout_throws_when_the_key_cannot_be_deleted_but_values_are_already_gone() {
        val failing = JvmAesGcmCipher(throwOnDelete = IllegalStateException("alias survived"))
        val s = store(failing)
        s.setUid("uid-1")

        assertThrows(IllegalStateException::class.java) { s.logout() }
        assertTrue(prefs.all.isEmpty())
    }

    @Test fun read_returns_null_when_the_kek_is_missing() {
        val s = store()
        s.setUid("uid-1")

        cipher.deleted = true

        assertNull(s.uid())
    }

    @Test fun read_returns_null_on_a_damaged_blob() {
        val s = store()
        s.setUid("uid-1")
        val raw = Base64.getDecoder().decode(prefs.getString("uid", null))
        raw[raw.size - 1] = (raw[raw.size - 1].toInt() xor 0x55).toByte()
        prefs.edit().putString("uid", Base64.getEncoder().encodeToString(raw)).commit()

        assertNull(s.uid())
    }

    @Test fun logout_then_a_fresh_store_over_the_same_file_reads_null_for_every_field() {
        val first = store()
        first.setUid("uid-1")
        first.setAccessToken("a")
        first.setRefreshToken("r")
        first.setKeyPassword(byteArrayOf(1))
        first.setHumanVerificationToken("hv")
        first.setHumanVerificationTokenType("captcha")

        first.logout()
        val recreated = EncryptedSecretStore(
            context.getSharedPreferences("test_auth", Context.MODE_PRIVATE),
            JvmAesGcmCipher(),
            logger
        )

        assertNull(recreated.uid())
        assertNull(recreated.accessToken())
        assertNull(recreated.refreshToken())
        assertNull(recreated.keyPassword())
        assertNull(recreated.humanVerificationToken())
        assertNull(recreated.humanVerificationTokenType())
    }

    @Test fun create_purges_the_legacy_file_and_its_master_key() {
        context.getSharedPreferences(EncryptedSecretStore.LEGACY_FILE_NAME, Context.MODE_PRIVATE)
            .edit().putString("__androidx_security_crypto_encrypted_prefs_key_keyset__", "x").commit()
        val legacyFile = File(
            context.applicationInfo.dataDir,
            "shared_prefs/${EncryptedSecretStore.LEGACY_FILE_NAME}.xml"
        )
        assertTrue(legacyFile.exists())
        val masterKey = JvmAesGcmCipher()

        assertTrue(EncryptedSecretStore.purgeLegacy(context, masterKey, logger))

        assertFalse(legacyFile.exists())
        assertTrue(masterKey.deleted)
    }

    @Test fun create_after_a_legacy_file_flags_the_storage_upgrade_for_the_ui() {
        context.getSharedPreferences(EncryptedSecretStore.LEGACY_FILE_NAME, Context.MODE_PRIVATE)
            .edit().putString("k", "x").commit()
        val userPreferences = SharedPreferencesUserPreferences(context)
        assertFalse(userPreferences.secretsStorageUpgraded)

        EncryptedSecretStore.create(context, logger)

        assertTrue(userPreferences.secretsStorageUpgraded)
    }

    @Test fun purge_is_a_noop_without_a_legacy_file() {
        val masterKey = JvmAesGcmCipher()

        assertFalse(EncryptedSecretStore.purgeLegacy(context, masterKey, logger))

        assertFalse(masterKey.deleted)
    }
}

/** AES-256-GCM in the JVM with the Keystore wrapper's wire format; `deleted` mimics a removed alias. */
private class JvmAesGcmCipher(private val throwOnDelete: Exception? = null) : SecretCipher {
    private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    var deleted = false

    override fun wrap(plaintext: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key)
        return c.iv + c.doFinal(plaintext)
    }

    override fun unwrap(wrapped: ByteArray): ByteArray {
        if (deleted) throw KekMissingException("test")
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, wrapped.copyOfRange(0, 12)))
        return c.doFinal(wrapped.copyOfRange(12, wrapped.size))
    }

    override fun delete() {
        throwOnDelete?.let { throw it }
        deleted = true
    }
}
