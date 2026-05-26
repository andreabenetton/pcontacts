// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.crypto.srp

import io.pcontacts.core.crypto.util.expandHash
import io.pcontacts.core.crypto.util.fromLittleEndianBigInteger
import io.pcontacts.core.crypto.util.toLittleEndianBytes
import io.pcontacts.core.crypto.util.toUnsignedBigInteger
import java.math.BigInteger
import java.security.SecureRandom

/**
 * Client side of Proton's custom SRP-6a variant.
 *
 * `[V]` Verified against `ProtonMail/go-srp` (`srp.go`, `hash.go`,
 * `server.go`). This is NOT standard RFC 5054 — Proton uses:
 *
 *   - **Little-endian** byte encoding for all BigInteger ↔ byte[]
 *     conversions (go-srp's `fromNat`/`toNat` reverse the byte order).
 *   - **expandHash** (4× SHA-512 = 256 bytes) instead of plain SHA-512
 *     for all hash operations (k, u, M1, M2).
 *   - **M1 = expandHash(A_LE ‖ B_LE ‖ S_LE)** — no identity, no salt,
 *     no H(N)⊕H(g) term. Completely different from RFC 5054's M1.
 *   - **M2 = expandHash(A_LE ‖ M1 ‖ S_LE)** — uses raw S, not K.
 *   - **k = fromLE(expandHash(g_LE ‖ N_LE)) mod N** — g before N.
 *   - **No K = H(S)** — the session key is the raw S little-endian bytes.
 *
 * The SRP arithmetic (S = (B − k·g^x)^(a + u·x) mod N) is standard.
 */
class SrpClient(
    private val random: SecureRandom = SecureRandom(),
    private val privateExponentBits: Int = 256
) {

    /**
     * @param N the modulus — decoded from the API's little-endian base64,
     *   reversed to big-endian BigInteger by the caller. Must already be
     *   signature-verified (ADR-0014).
     * @param g the SRP generator. Proton always uses 2.
     * @param serverEphemeralB the server's B value, decoded from the API's
     *   little-endian base64 and reversed to big-endian BigInteger.
     * @param x the private key derived from hashPassword — decoded from
     *   the little-endian expandHash output and reversed to BigInteger.
     * @return the proof triple including the client ephemeral, proofs, and
     *   expected server proof for verification.
     */
    fun login(
        N: BigInteger,
        g: BigInteger = G_DEFAULT,
        serverEphemeralB: BigInteger,
        x: BigInteger
    ): SrpProof {
        require(N.signum() > 0) { "modulus must be positive" }
        require(serverEphemeralB.signum() > 0) { "ServerEphemeral must be positive" }
        require(serverEphemeralB.mod(N) != BigInteger.ZERO) { "B mod N == 0 — abort" }
        val padLen = (N.bitLength() + 7) / 8

        val a = randomPrivateExponent(N)
        val A = g.modPow(a, N)
        require(A.mod(N) != BigInteger.ZERO) { "A mod N == 0 — abort" }

        val aLE = A.toLittleEndianBytes(padLen)
        val bLE = serverEphemeralB.toLittleEndianBytes(padLen)

        // [V] k = fromLE(expandHash(g_LE || N_LE)) mod N — g first per go-srp
        val k = expandHash(g.toLittleEndianBytes(padLen) + N.toLittleEndianBytes(padLen))
            .fromLittleEndianBigInteger().mod(N)

        // [V] u = fromLE(expandHash(A_LE || B_LE))
        val u = expandHash(aLE + bLE).fromLittleEndianBigInteger()
        require(u != BigInteger.ZERO) { "u == 0 — abort" }

        // S = (B - k * g^x mod N)^(a + u*x) mod N — standard arithmetic
        val gxModN = g.modPow(x, N)
        val base = (serverEphemeralB - (k * gxModN).mod(N)).mod(N)
        val exponent = a + (u * x)
        val S = base.modPow(exponent, N)

        val sLE = S.toLittleEndianBytes(padLen)

        // [V] M1 = expandHash(A_LE || B_LE || S_LE) — no identity/salt/N/g
        val M1 = expandHash(aLE + bLE + sLE)

        // [V] M2 = expandHash(A_LE || M1 || S_LE) — raw S, not K
        val expectedM2 = expandHash(aLE + M1 + sLE)

        return SrpProof(
            clientEphemeralA = A,
            clientProofM1 = M1,
            expectedServerProofM2 = expectedM2,
            sharedSessionKey = sLE
        )
    }

    fun verifyServerProof(serverProof: ByteArray, expected: ByteArray): Boolean {
        if (serverProof.size != expected.size) return false
        var diff = 0
        for (i in serverProof.indices) diff = diff or (serverProof[i].toInt() xor expected[i].toInt())
        return diff == 0
    }

    private fun randomPrivateExponent(N: BigInteger): BigInteger {
        val bytes = ByteArray(privateExponentBits / 8)
        do {
            random.nextBytes(bytes)
        } while (bytes.toUnsignedBigInteger() == BigInteger.ZERO)
        return bytes.toUnsignedBigInteger().mod(N - BigInteger.ONE) + BigInteger.ONE
    }

    companion object {
        val G_DEFAULT: BigInteger = BigInteger.valueOf(2)
    }
}

data class SrpProof(
    val clientEphemeralA: BigInteger,
    val clientProofM1: ByteArray,
    val expectedServerProofM2: ByteArray,
    val sharedSessionKey: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SrpProof) return false
        return clientEphemeralA == other.clientEphemeralA &&
            clientProofM1.contentEquals(other.clientProofM1) &&
            expectedServerProofM2.contentEquals(other.expectedServerProofM2) &&
            sharedSessionKey.contentEquals(other.sharedSessionKey)
    }

    override fun hashCode(): Int {
        var result = clientEphemeralA.hashCode()
        result = 31 * result + clientProofM1.contentHashCode()
        result = 31 * result + expectedServerProofM2.contentHashCode()
        result = 31 * result + sharedSessionKey.contentHashCode()
        return result
    }
}
