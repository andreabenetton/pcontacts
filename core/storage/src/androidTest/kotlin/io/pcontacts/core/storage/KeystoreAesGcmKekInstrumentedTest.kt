// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.KeyStore
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs against the real AndroidKeyStore (unit tests cannot: Robolectric
 * has no provider). The StrongBox branch is exercised only on hardware
 * that has one; the emulator takes the TEE fallback.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreAesGcmKekInstrumentedTest {

    private val alias = "pcontacts.test.${UUID.randomUUID()}"
    private val kek = KeystoreAesGcmKek(alias)

    @After fun cleanUp() {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.let { if (it.containsAlias(alias)) it.deleteEntry(alias) }
    }

    @Test fun wrap_then_unwrap_round_trips() {
        val plaintext = "key-password-bytes".encodeToByteArray()

        val wrapped = kek.wrap(plaintext)

        assertFalse(wrapped.contentEquals(plaintext))
        assertArrayEquals(plaintext, kek.unwrap(wrapped))
    }

    @Test fun unwrap_after_delete_throws_and_does_not_recreate_the_alias() {
        val wrapped = kek.wrap(byteArrayOf(1, 2, 3))
        kek.delete()

        assertThrows(KekMissingException::class.java) { kek.unwrap(wrapped) }
        assertFalse(aliasExists())
    }

    @Test fun wrap_after_delete_provisions_a_fresh_key() {
        val before = kek.wrap(byteArrayOf(9))
        kek.delete()

        val after = kek.wrap(byteArrayOf(9))

        assertTrue(aliasExists())
        assertThrows(Exception::class.java) { kek.unwrap(before) }
        assertArrayEquals(byteArrayOf(9), kek.unwrap(after))
    }

    @Test fun delete_is_idempotent() {
        kek.delete()
        kek.delete()

        assertFalse(aliasExists())
    }

    private fun aliasExists(): Boolean =
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(alias)
}
