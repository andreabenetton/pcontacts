// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.crypto.srp

import io.pcontacts.core.crypto.util.toUnsignedBigInteger
import io.pcontacts.core.crypto.util.toUnsignedBytes
import java.math.BigInteger
import java.security.SecureRandom
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SrpClientTest {

    /**
     * RFC 3526 §2 — 1024-bit MODP group (the smallest standard SRP group).
     * Useful for fast tests; production uses the 2048-bit Proton modulus.
     */
    private val N1024 = BigInteger(
        "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E08" +
            "8A67CC74020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B" +
            "302B0A6DF25F14374FE1356D6D51C245E485B576625E7EC6F44C42E9" +
            "A637ED6B0BFF5CB6F406B7EDEE386BFB5A899FA5AE9F24117C4B1FE6" +
            "49286651ECE65381FFFFFFFFFFFFFFFF",
        16
    )

    private val g2 = BigInteger.TWO

    @Test fun login_produces_consistent_self_round_trip() {
        // Deterministic random for repeatability.
        val client = SrpClient(random = SecureRandom.getInstance("SHA1PRNG").apply { setSeed(byteArrayOf(1, 2, 3, 4)) })

        // Synthetic server B and x — we're not verifying against a real
        // server here, just that the client computes a consistent
        // (A, M1, expectedM2, K) tuple and that running login twice with the
        // same inputs+seed yields identical outputs.
        val B = BigInteger("11" + "0".repeat(60), 16)
        val x = BigInteger("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", 16)
        val salt = ByteArray(16) { it.toByte() }

        val first = client.login(N = N1024, g = g2, salt = salt, serverEphemeralB = B, x = x)

        val client2 = SrpClient(random = SecureRandom.getInstance("SHA1PRNG").apply { setSeed(byteArrayOf(1, 2, 3, 4)) })
        val second = client2.login(N = N1024, g = g2, salt = salt, serverEphemeralB = B, x = x)

        assertEquals(first.clientEphemeralA, second.clientEphemeralA)
        assertArrayEquals(first.clientProofM1, second.clientProofM1)
        assertArrayEquals(first.expectedServerProofM2, second.expectedServerProofM2)
        assertArrayEquals(first.sharedKeyK, second.sharedKeyK)
    }

    @Test fun different_password_x_yields_different_proof_when_random_seeded_identically() {
        // A = g^a mod N depends only on `a` (and N, g); x affects only S/K/M1.
        // Re-seeding identically across clients pins `a`, isolating the
        // effect of x on the downstream proof.
        val B = BigInteger("22" + "0".repeat(60), 16)
        val salt = ByteArray(16) { it.toByte() }
        val xA = BigInteger("aaaaaaaa", 16)
        val xB = BigInteger("bbbbbbbb", 16)

        val clientA = SrpClient(random = SecureRandom.getInstance("SHA1PRNG").apply { setSeed(byteArrayOf(9, 9, 9)) })
        val a = clientA.login(N = N1024, g = g2, salt = salt, serverEphemeralB = B, x = xA)

        val clientB = SrpClient(random = SecureRandom.getInstance("SHA1PRNG").apply { setSeed(byteArrayOf(9, 9, 9)) })
        val b = clientB.login(N = N1024, g = g2, salt = salt, serverEphemeralB = B, x = xB)

        assertEquals("A should be identical when a is identical", a.clientEphemeralA, b.clientEphemeralA)
        assertFalse("M1 must differ when x differs", a.clientProofM1.contentEquals(b.clientProofM1))
        assertFalse("K must differ when x differs", a.sharedKeyK.contentEquals(b.sharedKeyK))
        assertFalse("expected M2 must differ when x differs", a.expectedServerProofM2.contentEquals(b.expectedServerProofM2))
    }

    @Test fun A_is_in_correct_padded_form_for_modulus() {
        val client = SrpClient()
        val proof = client.login(
            N = N1024, g = g2, salt = ByteArray(16),
            serverEphemeralB = BigInteger("ff" + "0".repeat(60), 16),
            x = BigInteger("1234", 16)
        )
        val padLen = (N1024.bitLength() + 7) / 8
        // Encoding A through toUnsignedBytes should round-trip cleanly.
        val encoded = proof.clientEphemeralA.toUnsignedBytes(padLen)
        assertEquals(padLen, encoded.size)
        assertEquals(proof.clientEphemeralA, encoded.toUnsignedBigInteger())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejects_B_that_is_zero_mod_N() {
        val client = SrpClient()
        client.login(
            N = N1024, g = g2, salt = ByteArray(16),
            serverEphemeralB = N1024,                          // B mod N == 0
            x = BigInteger.ONE
        )
    }

    @Test fun verifies_matching_server_proof() {
        val client = SrpClient()
        val proof = client.login(
            N = N1024, g = g2, salt = ByteArray(16),
            serverEphemeralB = BigInteger("aa" + "0".repeat(60), 16),
            x = BigInteger("5", 16)
        )
        assertTrue(client.verifyServerProof(proof.expectedServerProofM2, proof.expectedServerProofM2))
    }

    @Test fun rejects_mismatched_server_proof() {
        val client = SrpClient()
        val proof = client.login(
            N = N1024, g = g2, salt = ByteArray(16),
            serverEphemeralB = BigInteger("aa" + "0".repeat(60), 16),
            x = BigInteger("5", 16)
        )
        val tampered = proof.expectedServerProofM2.copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertFalse(client.verifyServerProof(tampered, proof.expectedServerProofM2))
    }

    @Test fun rejects_size_mismatched_server_proof() {
        val client = SrpClient()
        val proof = client.login(
            N = N1024, g = g2, salt = ByteArray(16),
            serverEphemeralB = BigInteger("aa" + "0".repeat(60), 16),
            x = BigInteger("5", 16)
        )
        assertFalse(client.verifyServerProof(byteArrayOf(0, 1, 2), proof.expectedServerProofM2))
    }
}
