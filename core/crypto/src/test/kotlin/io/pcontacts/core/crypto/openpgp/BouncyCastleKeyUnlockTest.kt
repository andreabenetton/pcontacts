// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.crypto.openpgp

import org.bouncycastle.bcpg.sig.KeyFlags
import org.bouncycastle.openpgp.PGPEncryptedDataList
import org.bouncycastle.openpgp.PGPPublicKeyEncryptedData
import org.bouncycastle.openpgp.PGPSecretKeyRing
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.bc.BcPGPObjectFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

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
            decryptionKeys = unlocked.allPrivateKeys,
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

    @Test fun a_key_that_does_not_open_says_which_key_and_protection_it_is() {
        // Issue #65: a log line of only KEY_UNLOCK_FAILED cannot tell a wrong passphrase from a
        // protection BouncyCastle does not support. Key metadata is public and safe to log.
        val armored = TestKeyGen.rsa2048Armored(passphrase = "P4ss-Z73-correct-horse".toCharArray())

        val ex = assertThrows(KeyUnlockException::class.java) {
            BouncyCastleKeyUnlock.unlock(armored, passphrase = "P4ss-Z73-NOT-the-key".toCharArray())
        }

        val message = ex.message!!
        assertTrue(message, message.contains("key v4"))
        assertTrue(message, message.contains("algorithm "))
        assertTrue(message, message.contains("s2k usage "))
        assertTrue(message, !message.contains("P4ss-Z73"))
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

    @Test fun a_sign_only_rsa_primary_is_not_an_encryption_key_its_encryption_subkey_is() {
        // Issue #71: RSA can encrypt by algorithm, but Proton flags the primary sign-only. A card
        // encrypted to both the primary and the subkey was refused with Code 2060 (PgpFormat).
        val armored = TestKeyGen.rsaRingArmored(
            passphrase = PASSPHRASE.copyOf(),
            primaryFlags = SIGN_ONLY,
            subkeys = listOf(ENCRYPT to Date())
        )

        val unlocked = BouncyCastleKeyUnlock.unlock(armored, PASSPHRASE.copyOf())

        val subkeyId = subkeyIds(armored).single()
        assertEquals(listOf(subkeyId), unlocked.encryptionPublicKeys.map { it.raw.keyID })
        val message = BouncyCastleOpenPgpService().encryptAndSignDetached(
            plaintext = "BEGIN:VCARD\r\nEND:VCARD".toByteArray(),
            encryptionKeys = unlocked.encryptionPublicKeys,
            signingKey = unlocked.private
        ).armoredMessage
        assertEquals(listOf(subkeyId), recipients(message))
    }

    @Test fun of_two_encryption_subkeys_the_newest_is_used() {
        val armored = TestKeyGen.rsaRingArmored(
            passphrase = PASSPHRASE.copyOf(),
            primaryFlags = SIGN_ONLY,
            subkeys = listOf(ENCRYPT to Date(OLDER), ENCRYPT to Date(NEWER))
        )

        val unlocked = BouncyCastleKeyUnlock.unlock(armored, PASSPHRASE.copyOf())

        assertEquals(listOf(subkeyIds(armored).last()), unlocked.encryptionPublicKeys.map { it.raw.keyID })
    }

    @Test fun a_primary_flagged_for_encryption_is_used_when_no_subkey_is() {
        val armored = TestKeyGen.rsaRingArmored(
            passphrase = PASSPHRASE.copyOf(),
            primaryFlags = SIGN_ONLY or ENCRYPT,
            subkeys = listOf(KeyFlags.SIGN_DATA to Date())
        )

        val unlocked = BouncyCastleKeyUnlock.unlock(armored, PASSPHRASE.copyOf())

        assertEquals(listOf(unlocked.public.raw.keyID), unlocked.encryptionPublicKeys.map { it.raw.keyID })
    }

    @Test fun a_ring_with_no_key_flagged_for_encryption_has_no_encryption_key() {
        val armored = TestKeyGen.rsaRingArmored(
            passphrase = PASSPHRASE.copyOf(),
            primaryFlags = SIGN_ONLY,
            subkeys = listOf(KeyFlags.SIGN_DATA to Date())
        )

        val unlocked = BouncyCastleKeyUnlock.unlock(armored, PASSPHRASE.copyOf())

        assertTrue(unlocked.encryptionPublicKeys.isEmpty())
    }

    private fun subkeyIds(armored: String): List<Long> {
        val ring = firstObject(armored) as PGPSecretKeyRing
        return ring.publicKeys.asSequence().filterNot { it.isMasterKey }.map { it.keyID }.toList()
    }

    private fun recipients(armoredMessage: String): List<Long> {
        val list = firstObject(armoredMessage) as PGPEncryptedDataList
        return list.encryptedDataObjects.asSequence()
            .filterIsInstance<PGPPublicKeyEncryptedData>()
            .map { it.keyID }
            .toList()
    }

    private fun firstObject(armored: String): Any =
        BcPGPObjectFactory(PGPUtil.getDecoderStream(armored.byteInputStream())).nextObject()

    private companion object {
        val PASSPHRASE = "P4ss-Z73-ring".toCharArray()
        const val SIGN_ONLY = KeyFlags.CERTIFY_OTHER or KeyFlags.SIGN_DATA
        const val ENCRYPT = KeyFlags.ENCRYPT_COMMS or KeyFlags.ENCRYPT_STORAGE
        const val OLDER = 1_700_000_000_000L
        const val NEWER = 1_750_000_000_000L
    }
}
