// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.crypto.openpgp

import org.bouncycastle.bcpg.sig.KeyFlags
import org.bouncycastle.openpgp.PGPPublicKey
import org.bouncycastle.openpgp.PGPSecretKey
import org.bouncycastle.openpgp.PGPSecretKeyRing
import org.bouncycastle.openpgp.PGPSignature
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Date

/**
 * The signature that decides the encryption key is the newest one in force now, and a key or a
 * signature dated in the future does not count yet, as in OpenPGP.js (review 2026-11-18, R3).
 * The same cases, made and answered by OpenPGP.js, are in the captured vectors.
 */
class KeySelectionTimeTest {

    @Test fun an_older_binding_still_in_force_outlives_a_newer_one_that_lapsed() {
        val ring = ring(listOf(ENCRYPT to OLD))
        val sub = subkeys(ring).single()
        val older = binding(ring, sub, created = OLD)
        val lapsed = binding(ring, sub, created = RECENT, signatureExpirySeconds = HOUR_SECONDS)

        val chosen = encryptionKeyIds(ring, sub, PGPPublicKey.addCertification(rebound(sub, older), lapsed))

        assertEquals(listOf(sub.keyID), chosen)
    }

    @Test fun a_subkey_whose_only_binding_lapsed_is_not_used() {
        val ring = ring(listOf(ENCRYPT to OLD))
        val sub = subkeys(ring).single()
        val lapsed = binding(ring, sub, created = RECENT, signatureExpirySeconds = HOUR_SECONDS)

        val chosen = encryptionKeyIds(ring, sub, rebound(sub, lapsed))

        assertEquals(emptyList<Long>(), chosen)
    }

    @Test fun a_binding_dated_in_the_future_does_not_count_yet() {
        val ring = ring(listOf(ENCRYPT to OLD))
        val sub = subkeys(ring).single()
        val future = binding(ring, sub, created = FUTURE)

        val chosen = encryptionKeyIds(ring, sub, rebound(sub, future))

        assertEquals(emptyList<Long>(), chosen)
    }

    @Test fun a_binding_dated_in_the_future_does_not_displace_the_one_in_force() {
        // The future one carries no encryption flag: were it chosen, the subkey would be dropped.
        val ring = ring(listOf(ENCRYPT to OLD))
        val sub = subkeys(ring).single()
        val future = binding(ring, sub, created = FUTURE, flags = KeyFlags.SIGN_DATA)

        val chosen = encryptionKeyIds(ring, sub, PGPPublicKey.addCertification(sub.publicKey, future))

        assertEquals(listOf(sub.keyID), chosen)
    }

    @Test fun a_subkey_dated_in_the_future_is_passed_over_for_one_that_exists() {
        val ring = ring(listOf(ENCRYPT to OLD, ENCRYPT to FUTURE))
        val existing = subkeys(ring).single { it.publicKey.creationTime == OLD }

        val chosen = BouncyCastleKeyUnlock.unlock(TestKeyGen.armor(ring), PASSPHRASE.copyOf())
            .encryptionPublicKeys.map { it.raw.keyID }

        assertEquals(listOf(existing.keyID), chosen)
    }

    @Test fun a_ring_whose_only_encryption_subkey_is_dated_in_the_future_has_no_encryption_key() {
        val ring = TestKeyGen.parseRing(
            TestKeyGen.rsaRingArmored(PASSPHRASE.copyOf(), SIGN_ONLY, listOf(SIGN_ONLY to OLD, ENCRYPT to FUTURE))
        )

        val chosen = BouncyCastleKeyUnlock.unlock(TestKeyGen.armor(ring), PASSPHRASE.copyOf())
            .encryptionPublicKeys.map { it.raw.keyID }

        assertEquals(emptyList<Long>(), chosen)
    }

    private fun ring(subkeys: List<Pair<Int, Date>>): PGPSecretKeyRing =
        TestKeyGen.parseRing(TestKeyGen.rsaRingArmored(PASSPHRASE.copyOf(), SIGN_ONLY, subkeys))

    private fun subkeys(ring: PGPSecretKeyRing): List<PGPSecretKey> =
        ring.secretKeys.asSequence().filter { !it.isMasterKey }.toList()

    private fun binding(
        ring: PGPSecretKeyRing,
        sub: PGPSecretKey,
        created: Date,
        signatureExpirySeconds: Long = 0L,
        flags: Int = ENCRYPT
    ): PGPSignature = TestKeyGen.signature(
        PGPSignature.SUBKEY_BINDING,
        TestKeyGen.primaryPrivate(ring, PASSPHRASE.copyOf()),
        primary = ring.publicKey,
        subkey = sub.publicKey,
        flags = flags,
        created = created,
        signatureExpirySeconds = signatureExpirySeconds
    )

    /** [sub]'s public key with [binding] as its only binding. */
    private fun rebound(sub: PGPSecretKey, binding: PGPSignature): PGPPublicKey = PGPPublicKey.addCertification(
        TestKeyGen.withoutSignatures(sub.publicKey, PGPSignature.SUBKEY_BINDING),
        binding
    )

    private fun encryptionKeyIds(ring: PGPSecretKeyRing, sub: PGPSecretKey, public: PGPPublicKey): List<Long> =
        BouncyCastleKeyUnlock.unlock(TestKeyGen.armor(TestKeyGen.withPublic(ring, sub, public)), PASSPHRASE.copyOf())
            .encryptionPublicKeys.map { it.raw.keyID }

    private companion object {
        val PASSPHRASE = "P4ss-Z73-time".toCharArray()
        const val SIGN_ONLY = KeyFlags.CERTIFY_OTHER or KeyFlags.SIGN_DATA
        const val ENCRYPT = KeyFlags.ENCRYPT_COMMS or KeyFlags.ENCRYPT_STORAGE
        const val HOUR_SECONDS = 3_600L
        const val DAY_MILLIS = 86_400_000L
        val OLD = Date(1_700_000_000_000L)
        val RECENT: Date get() = Date(System.currentTimeMillis() - 2 * DAY_MILLIS)
        val FUTURE = Date(4_000_000_000_000L)
    }
}
