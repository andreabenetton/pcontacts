// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.crypto.openpgp

import org.bouncycastle.bcpg.sig.KeyFlags
import org.bouncycastle.openpgp.PGPEncryptedDataList
import org.bouncycastle.openpgp.PGPPublicKey
import org.bouncycastle.openpgp.PGPPublicKeyEncryptedData
import org.bouncycastle.openpgp.PGPSecretKeyRing
import org.bouncycastle.openpgp.PGPSignature
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
        assertEquals("v4 algorithm 1", unlocked.public.description)
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

    @Test fun a_planted_subkey_whose_binding_only_names_the_primary_is_not_used() {
        // Review 2026-11-17, F1: the ring arrives from the server. A newer subkey whose binding
        // claims the primary as issuer but was made with another key must not become the recipient.
        val ring = TestKeyGen.parseRing(
            TestKeyGen.rsaRingArmored(PASSPHRASE.copyOf(), SIGN_ONLY, listOf(ENCRYPT to Date(OLDER)))
        )
        val legit = ring.publicKeys.asSequence().single { !it.isMasterKey }.keyID
        val attacker = TestKeyGen.parseRing(
            TestKeyGen.rsaRingArmored(OTHER.copyOf(), SIGN_ONLY, listOf(ENCRYPT to Date(NEWER)))
        )
        val attackerKey = TestKeyGen.primaryPrivate(attacker, OTHER.copyOf())
        val planted = TestKeyGen.withoutSignatures(
            attacker.publicKeys.asSequence().single { !it.isMasterKey },
            PGPSignature.SUBKEY_BINDING
        )
        val forged = TestKeyGen.signature(
            PGPSignature.SUBKEY_BINDING,
            attackerKey,
            primary = ring.publicKey,
            subkey = planted,
            flags = ENCRYPT,
            claimedIssuer = ring.publicKey.keyID
        )
        val tampered = PGPSecretKeyRing.insertOrReplacePublicKey(ring, PGPPublicKey.addCertification(planted, forged))

        val unlocked = BouncyCastleKeyUnlock.unlock(TestKeyGen.armor(tampered), PASSPHRASE.copyOf())

        assertEquals(listOf(legit), unlocked.encryptionPublicKeys.map { it.raw.keyID })
    }

    @Test fun a_revocation_the_primary_did_not_make_is_ignored() {
        val ring = TestKeyGen.parseRing(
            TestKeyGen.rsaRingArmored(PASSPHRASE.copyOf(), SIGN_ONLY, listOf(ENCRYPT to Date(OLDER)))
        )
        val attacker = TestKeyGen.parseRing(TestKeyGen.rsaRingArmored(OTHER.copyOf(), SIGN_ONLY, emptyList()))
        val sub = ring.secretKeys.asSequence().single { !it.isMasterKey }
        val forged = TestKeyGen.signature(
            PGPSignature.SUBKEY_REVOCATION,
            TestKeyGen.primaryPrivate(attacker, OTHER.copyOf()),
            primary = ring.publicKey,
            subkey = sub.publicKey,
            claimedIssuer = ring.publicKey.keyID
        )
        val tampered = TestKeyGen.withPublic(ring, sub, PGPPublicKey.addCertification(sub.publicKey, forged))

        val unlocked = BouncyCastleKeyUnlock.unlock(TestKeyGen.armor(tampered), PASSPHRASE.copyOf())

        assertEquals(listOf(sub.keyID), unlocked.encryptionPublicKeys.map { it.raw.keyID })
    }

    @Test fun a_subkey_the_primary_revoked_is_not_used() {
        val ring = TestKeyGen.parseRing(
            TestKeyGen.rsaRingArmored(PASSPHRASE.copyOf(), SIGN_ONLY, listOf(ENCRYPT to Date(OLDER)))
        )
        val sub = ring.secretKeys.asSequence().single { !it.isMasterKey }
        val revocation = TestKeyGen.signature(
            PGPSignature.SUBKEY_REVOCATION,
            TestKeyGen.primaryPrivate(ring, PASSPHRASE.copyOf()),
            primary = ring.publicKey,
            subkey = sub.publicKey
        )
        val revoked = TestKeyGen.withPublic(ring, sub, PGPPublicKey.addCertification(sub.publicKey, revocation))

        val unlocked = BouncyCastleKeyUnlock.unlock(TestKeyGen.armor(revoked), PASSPHRASE.copyOf())

        assertTrue(unlocked.encryptionPublicKeys.isEmpty())
    }

    @Test fun a_subkey_past_the_expiry_its_binding_sets_is_not_used() {
        val created = Date(System.currentTimeMillis() - 2 * DAY_MILLIS)
        val ring = TestKeyGen.parseRing(
            TestKeyGen.rsaRingArmored(PASSPHRASE.copyOf(), SIGN_ONLY, listOf(ENCRYPT to created))
        )
        val sub = ring.secretKeys.asSequence().single { !it.isMasterKey }
        val expiring = TestKeyGen.signature(
            PGPSignature.SUBKEY_BINDING,
            TestKeyGen.primaryPrivate(ring, PASSPHRASE.copyOf()),
            primary = ring.publicKey,
            subkey = sub.publicKey,
            flags = ENCRYPT,
            keyExpirySeconds = DAY_MILLIS / 1000
        )
        val rebound = PGPPublicKey.addCertification(
            TestKeyGen.withoutSignatures(sub.publicKey, PGPSignature.SUBKEY_BINDING),
            expiring
        )

        val unlocked = BouncyCastleKeyUnlock.unlock(
            TestKeyGen.armor(TestKeyGen.withPublic(ring, sub, rebound)),
            PASSPHRASE.copyOf()
        )

        assertTrue(unlocked.encryptionPublicKeys.isEmpty())
    }

    @Test fun a_primary_whose_self_certification_was_forged_gives_no_encryption_key() {
        val ring = TestKeyGen.parseRing(
            TestKeyGen.rsaRingArmored(PASSPHRASE.copyOf(), SIGN_ONLY or ENCRYPT, emptyList())
        )
        val attacker = TestKeyGen.parseRing(TestKeyGen.rsaRingArmored(OTHER.copyOf(), SIGN_ONLY, emptyList()))
        val forged = TestKeyGen.signature(
            PGPSignature.POSITIVE_CERTIFICATION,
            TestKeyGen.primaryPrivate(attacker, OTHER.copyOf()),
            primary = ring.publicKey,
            userId = USER_ID,
            flags = SIGN_ONLY or ENCRYPT,
            claimedIssuer = ring.publicKey.keyID
        )
        val recertified = PGPPublicKey.addCertification(
            PGPPublicKey.removeCertification(ring.publicKey, USER_ID),
            USER_ID,
            forged
        )

        val unlocked = BouncyCastleKeyUnlock.unlock(
            TestKeyGen.armor(TestKeyGen.withPublic(ring, ring.secretKey, recertified)),
            PASSPHRASE.copyOf()
        )

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
        const val DAY_MILLIS = 86_400_000L
        const val USER_ID = "pcontacts-test"
        val OTHER = "P4ss-Z73-other".toCharArray()
    }
}
