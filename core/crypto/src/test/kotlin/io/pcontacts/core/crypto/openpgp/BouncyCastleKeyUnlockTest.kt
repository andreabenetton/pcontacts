// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.crypto.openpgp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for `BouncyCastleKeyUnlock`. Verifies:
 *   - happy path: armored block + correct passphrase → unlocked handles
 *     that round-trip a real encrypt/decrypt operation
 *   - wrong passphrase: throws KeyUnlockException without leaking the
 *     passphrase
 *   - malformed armored input: throws KeyUnlockException
 *   - empty armored block: throws KeyUnlockException
 */
class BouncyCastleKeyUnlockTest {

    @Test fun unlock_with_correct_passphrase_returns_handles_that_decrypt_a_round_trip_message() {
        val passphrase = "correct-passphrase".toCharArray()
        val armored = TestKeyGen.rsa2048Armored(passphrase = passphrase.copyOf())

        val unlocked = BouncyCastleKeyUnlock.unlock(armored, passphrase = passphrase)

        // Sanity: handles are non-null and share a key id.
        assertNotNull(unlocked.private)
        assertNotNull(unlocked.public)
        assertEquals(unlocked.private.keyIdHex, unlocked.public.keyIdHex)

        // Round-trip: encrypt+sign with this keypair, then decrypt with the
        // unlocked handle. Proves the unlocked private key is functional.
        val openPgp = BouncyCastleOpenPgpService()
        val plaintext = "BEGIN:VCARD\r\nFN:Alice\r\nEND:VCARD".toByteArray()
        val encrypted = openPgp.encryptAndSignDetached(
            plaintext = plaintext,
            encryptionKeys = listOf(unlocked.public),
            signingKey = unlocked.private
        )
        val decrypted = openPgp.decryptAndVerify(
            armoredMessage = encrypted.armoredMessage,
            detachedSignature = encrypted.armoredDetachedSignature,
            decryptionKey = unlocked.private,
            verificationKeys = listOf(unlocked.public)
        )
        assertTrue(plaintext.contentEquals(decrypted.plaintext))
        assertEquals(VerificationStatus.SIGNED_AND_VALID, decrypted.verificationStatus)
    }

    @Test fun unlock_with_wrong_passphrase_throws_KeyUnlockException() {
        // Use a distinctive, non-English passphrase so the "must not leak"
        // assertion below isn't confused with the word "wrong" appearing in
        // the generic error message.
        val rightPassphrase = "P4ss-Z73-correct-horse".toCharArray()
        val wrongPassphrase = "P4ss-Z73-NOT-the-key".toCharArray()
        val armored = TestKeyGen.rsa2048Armored(passphrase = rightPassphrase)

        val ex = assertThrows(KeyUnlockException::class.java) {
            BouncyCastleKeyUnlock.unlock(armored, passphrase = wrongPassphrase)
        }
        // Error message must not echo the attempted passphrase verbatim.
        assertTrue("error message must not contain the supplied passphrase",
            !ex.message!!.contains(String(wrongPassphrase)))
        // Cause must be the underlying BouncyCastle PGPException — useful
        // for support without leaking secrets.
        assertNotNull(ex.cause)
    }

    @Test fun unlock_with_malformed_armored_throws_KeyUnlockException() {
        val ex = assertThrows(KeyUnlockException::class.java) {
            BouncyCastleKeyUnlock.unlock(
                armoredPrivateKey = "this is not a pgp block",
                passphrase = "x".toCharArray()
            )
        }
        assertTrue(ex.message!!.contains("PGPSecretKeyRing") || ex.message!!.contains("parse"))
    }

    @Test fun unlock_with_empty_input_throws_KeyUnlockException() {
        assertThrows(KeyUnlockException::class.java) {
            BouncyCastleKeyUnlock.unlock(armoredPrivateKey = "", passphrase = CharArray(0))
        }
    }
}
