// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.sync.contacts.decrypt

import io.pcontacts.core.crypto.openpgp.EncryptedSignedResult
import io.pcontacts.core.crypto.openpgp.OpenPgpService
import io.pcontacts.core.crypto.openpgp.PgpPrivateKeyHandle
import io.pcontacts.core.crypto.openpgp.PgpPublicKeyHandle
import io.pcontacts.core.crypto.openpgp.VerificationStatus
import io.pcontacts.core.crypto.openpgp.VerifiedDecryptResult
import io.pcontacts.core.protoncontacts.CardCryptoRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Adapter unit tests with a fake OpenPgpService. The integration test
 * that exercises real BouncyCastle crypto end-to-end lives in
 * ContactDecryptBootstrapTest.
 */
class OpenPgpCardCryptoOpTest {

    private lateinit var fake: FakeOpenPgpService
    private lateinit var dummyPriv: PgpPrivateKeyHandle
    private lateinit var dummyPub: PgpPublicKeyHandle

    @Before fun setUp() {
        // We need real handles to thread through the lambda, but the fake
        // OpenPgpService never inspects them. The handle constructors are
        // internal to :core:crypto, so we route through TestKeys (which
        // goes through the public BouncyCastleKeyUnlock surface).
        val (_, unlocked) = TestKeys.armoredAndUnlocked(passphrase = "throwaway".toCharArray())
        dummyPriv = unlocked.private
        dummyPub = unlocked.public
        fake = FakeOpenPgpService()
    }

    @Test fun verify_only_calls_verifyDetached_and_maps_SIGNED_AND_VALID_to_verified_true() {
        fake.verifyResult = VerificationStatus.SIGNED_AND_VALID
        val op = OpenPgpCardCryptoOp.build(fake, listOf(dummyPriv), listOf(dummyPub))

        val outcome = op(CardCryptoRequest.VerifyOnly(data = "FN:Alice", signature = "armored-sig"))

        assertEquals("FN:Alice", outcome.plaintext)
        assertTrue(outcome.verified)
        assertEquals(1, fake.verifyCalls)
        assertEquals(0, fake.decryptCalls)
    }

    @Test fun verify_only_maps_SIGNED_INVALID_to_verified_false() {
        fake.verifyResult = VerificationStatus.SIGNED_INVALID
        val op = OpenPgpCardCryptoOp.build(fake, listOf(dummyPriv), listOf(dummyPub))

        val outcome = op(CardCryptoRequest.VerifyOnly("FN:Alice", "tampered"))

        assertFalse(outcome.verified)
        assertEquals("FN:Alice", outcome.plaintext)   // plaintext retained per Plan §10.1
    }

    @Test fun decrypt_only_calls_decryptAndVerify_with_null_signature_and_returns_verified_true() {
        fake.decryptResult = VerifiedDecryptResult(
            plaintext = "TEL:+1 555 0000".toByteArray(),
            verificationStatus = VerificationStatus.NOT_SIGNED
        )
        val op = OpenPgpCardCryptoOp.build(fake, listOf(dummyPriv), listOf(dummyPub))

        val outcome = op(CardCryptoRequest.DecryptOnly(armored = "-----BEGIN PGP MESSAGE-----..."))

        assertEquals("TEL:+1 555 0000", outcome.plaintext)
        assertTrue("ENCRYPTED-only: no signature path, verified=true by policy", outcome.verified)
        assertEquals(1, fake.decryptCalls)
        assertNull("DecryptOnly must pass null detachedSignature", fake.lastDetachedSignature)
        assertEquals(0, fake.verifyCalls)
    }

    @Test fun decrypt_and_verify_passes_signature_through_and_maps_SIGNED_AND_VALID_to_verified_true() {
        fake.decryptResult = VerifiedDecryptResult(
            plaintext = "EMAIL:alice@proton.me".toByteArray(),
            verificationStatus = VerificationStatus.SIGNED_AND_VALID
        )
        val op = OpenPgpCardCryptoOp.build(fake, listOf(dummyPriv), listOf(dummyPub))

        val outcome = op(CardCryptoRequest.DecryptAndVerify(
            armored = "-----BEGIN PGP MESSAGE-----...",
            signature = "-----BEGIN PGP SIGNATURE-----..."
        ))

        assertEquals("EMAIL:alice@proton.me", outcome.plaintext)
        assertTrue(outcome.verified)
        assertEquals("-----BEGIN PGP SIGNATURE-----...", fake.lastDetachedSignature)
    }

    @Test fun decrypt_and_verify_with_SIGNED_INVALID_yields_verified_false_but_keeps_plaintext() {
        fake.decryptResult = VerifiedDecryptResult(
            plaintext = "EMAIL:alice@proton.me".toByteArray(),
            verificationStatus = VerificationStatus.SIGNED_INVALID
        )
        val op = OpenPgpCardCryptoOp.build(fake, listOf(dummyPriv), listOf(dummyPub))

        val outcome = op(CardCryptoRequest.DecryptAndVerify("msg", "sig"))

        assertEquals("EMAIL:alice@proton.me", outcome.plaintext)   // ADR-0007 / §10.1
        assertFalse(outcome.verified)
    }

    private class FakeOpenPgpService : OpenPgpService {
        var verifyCalls = 0
        var decryptCalls = 0
        var verifyResult = VerificationStatus.SIGNED_AND_VALID
        var decryptResult = VerifiedDecryptResult(ByteArray(0), VerificationStatus.NOT_SIGNED)
        var lastDetachedSignature: String? = null

        override fun encryptAndSignDetached(
            plaintext: ByteArray,
            encryptionKeys: List<PgpPublicKeyHandle>,
            signingKey: PgpPrivateKeyHandle
        ): EncryptedSignedResult = error("not used in adapter tests")

        override fun signDetached(
            plaintext: ByteArray,
            signingKey: PgpPrivateKeyHandle,
            canonicalText: Boolean,
            stripTrailingSpaces: Boolean
        ): String = error("not used in adapter tests")

        override fun decryptAndVerify(
            armoredMessage: String,
            detachedSignature: String?,
            decryptionKeys: List<PgpPrivateKeyHandle>,
            verificationKeys: List<PgpPublicKeyHandle>
        ): VerifiedDecryptResult {
            decryptCalls += 1
            lastDetachedSignature = detachedSignature
            return decryptResult
        }

        override fun verifyDetached(
            plaintext: ByteArray,
            armoredSignature: String,
            verificationKeys: List<PgpPublicKeyHandle>,
            canonicalText: Boolean,
            stripTrailingSpaces: Boolean
        ): VerificationStatus {
            verifyCalls += 1
            return verifyResult
        }
    }
}
