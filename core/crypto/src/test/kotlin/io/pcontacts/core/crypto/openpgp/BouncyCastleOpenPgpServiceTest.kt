// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.crypto.openpgp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

class BouncyCastleOpenPgpServiceTest {

    private val service = BouncyCastleOpenPgpService()

    @Test fun sign_then_verify_detached_round_trip_binary() {
        val plaintext = "the quick brown fox jumps over the lazy dog".toByteArray()
        val sig = service.signDetached(plaintext, key.priv, canonicalText = false)
        val status = service.verifyDetached(plaintext, sig, listOf(key.pub), canonicalText = false)
        assertEquals(VerificationStatus.SIGNED_AND_VALID, status)
    }

    @Test fun sign_then_verify_detached_round_trip_canonical_text_with_strip() {
        val original = "BEGIN:VCARD\nFN:Alice   \nEMAIL:alice@example.com  \nEND:VCARD\n"
        val sig = service.signDetached(original.toByteArray(), key.priv, canonicalText = true, stripTrailingSpaces = true)
        // Verifier re-canonicalizes; both sign- and verify-side strip trailing spaces.
        val status = service.verifyDetached(original.toByteArray(), sig, listOf(key.pub), canonicalText = true, stripTrailingSpaces = true)
        assertEquals(VerificationStatus.SIGNED_AND_VALID, status)
    }

    @Test fun verify_returns_invalid_when_payload_tampered() {
        val plaintext = "hello".toByteArray()
        val sig = service.signDetached(plaintext, key.priv, canonicalText = false)
        val tampered = "hellO".toByteArray()
        val status = service.verifyDetached(tampered, sig, listOf(key.pub), canonicalText = false)
        assertEquals(VerificationStatus.SIGNED_INVALID, status)
    }

    @Test fun verify_returns_no_verifier_when_keyId_mismatch() {
        val plaintext = "abc".toByteArray()
        val sig = service.signDetached(plaintext, key.priv, canonicalText = false)
        val otherKey = TestKeyGen.rsa2048("other")
        val status = service.verifyDetached(plaintext, sig, listOf(otherKey.pub), canonicalText = false)
        assertEquals(VerificationStatus.SIGNED_NO_VERIFIER, status)
    }

    @Test fun encrypt_and_sign_then_decrypt_and_verify_round_trip() {
        val plaintext = ("vCard payload — multi-line\n" +
            "field=value\n" +
            "another=field\n").toByteArray()

        val encrypted = service.encryptAndSignDetached(
            plaintext = plaintext,
            encryptionKeys = listOf(key.pub),
            signingKey = key.priv
        )

        assertTrue("message should be ASCII armored", encrypted.armoredMessage.contains("-----BEGIN PGP MESSAGE-----"))
        assertTrue("sig should be ASCII armored", encrypted.armoredDetachedSignature.contains("-----BEGIN PGP SIGNATURE-----"))

        val verified = service.decryptAndVerify(
            armoredMessage = encrypted.armoredMessage,
            detachedSignature = encrypted.armoredDetachedSignature,
            decryptionKey = key.priv,
            verificationKeys = listOf(key.pub)
        )

        assertArrayEquals(plaintext, verified.plaintext)
        assertEquals(VerificationStatus.SIGNED_AND_VALID, verified.verificationStatus)
    }

    @Test fun decrypt_with_no_detached_signature_yields_NOT_SIGNED() {
        val plaintext = "x".toByteArray()
        val encrypted = service.encryptAndSignDetached(
            plaintext = plaintext,
            encryptionKeys = listOf(key.pub),
            signingKey = key.priv
        )
        val result = service.decryptAndVerify(
            armoredMessage = encrypted.armoredMessage,
            detachedSignature = null,
            decryptionKey = key.priv,
            verificationKeys = listOf(key.pub)
        )
        assertArrayEquals(plaintext, result.plaintext)
        assertEquals(VerificationStatus.NOT_SIGNED, result.verificationStatus)
    }

    @Test fun decrypt_with_signature_but_no_verifier_yields_SIGNED_NO_VERIFIER() {
        val plaintext = "y".toByteArray()
        val encrypted = service.encryptAndSignDetached(
            plaintext = plaintext,
            encryptionKeys = listOf(key.pub),
            signingKey = key.priv
        )
        val result = service.decryptAndVerify(
            armoredMessage = encrypted.armoredMessage,
            detachedSignature = encrypted.armoredDetachedSignature,
            decryptionKey = key.priv,
            verificationKeys = emptyList()
        )
        assertEquals(VerificationStatus.SIGNED_NO_VERIFIER, result.verificationStatus)
    }

    companion object {
        // Reuse one keypair across the suite — RSA-2048 generation is the
        // slowest single operation in this test file.
        private lateinit var key: TestKeyGen.TestKey

        @BeforeClass @JvmStatic fun makeKey() {
            key = TestKeyGen.rsa2048()
        }
    }
}
