// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.crypto.srp

import io.pcontacts.core.crypto.util.sha512
import io.pcontacts.core.crypto.util.toUnsignedBigInteger
import io.pcontacts.core.crypto.util.toUnsignedBytes
import java.math.BigInteger
import java.security.SecureRandom

/**
 * Client side of SRP-6a per RFC 5054, parameterized for the Proton variant
 * (SHA-512 hash, x derived from `bcrypt-SHA512`, server-provided modulus).
 *
 * Verification markers:
 *   `[V]` RFC 5054 §2.5.3/§2.6 client computation — math is standards-compliant.
 *   `[A]` Proton's k = H(N | PAD(g)) per SRP-6a. Standard but worth a vector
 *         check against `@protontech/crypto/srp` once ADR-0013 lands.
 *   `[A]` Proton uses g = 2 across all sessions. Web client never overrides;
 *         server-provided modulus N is the only varying input. The `g` value
 *         is wired here as a default.
 *   `[A]` Client ephemeral private exponent `a` is sampled as 256 bits.
 *         Implementations vary 128–256 bits; 256 matches RFC 5054 §2.5.4
 *         security recommendation.
 *   `[U]` x derivation in the real Proton flow uses bcrypt-SHA512 of the
 *         mailbox password against the InfoResponse.Salt — handled by the
 *         caller (`ComputeKeyPassword`); this client takes the resulting
 *         BigInteger as input.
 */
class SrpClient(
    private val random: SecureRandom = SecureRandom(),
    private val privateExponentBits: Int = 256
) {

    /**
     * @param N the modulus from `InfoResponse.Modulus` — must already be
     *   signature-verified against the pinned Proton SRP key (ADR-0014).
     * @param g the SRP generator. Proton always uses 2.
     * @param salt the SRP salt from `InfoResponse.Salt`.
     * @param serverEphemeralB the server's B value from `InfoResponse.ServerEphemeral`.
     * @param x the private key derived from the password+salt — for the
     *   Proton variant this is `BigInteger.fromUnsignedBytes(bcrypt-SHA512(password, salt))`.
     * @return the proof triple to send the server, plus the expected M2 to
     *   validate the server's `ServerProof` against.
     */
    fun login(
        N: BigInteger,
        g: BigInteger = G_DEFAULT,
        salt: ByteArray,
        serverEphemeralB: BigInteger,
        x: BigInteger,
        username: String = USERNAME_FIXED
    ): SrpProof {
        require(N.signum() > 0) { "modulus must be positive" }
        require(serverEphemeralB.signum() > 0) { "ServerEphemeral must be positive" }
        require(serverEphemeralB.mod(N) != BigInteger.ZERO) { "B mod N == 0 — abort per RFC 5054" }
        val padLen = (N.bitLength() + 7) / 8

        val a = randomPrivateExponent(N)
        val A = g.modPow(a, N)
        require(A.mod(N) != BigInteger.ZERO) { "A mod N == 0 — abort per RFC 5054" }

        val u = hashToInt(A.toUnsignedBytes(padLen), serverEphemeralB.toUnsignedBytes(padLen))
        require(u != BigInteger.ZERO) { "u == 0 — abort per RFC 5054" }

        val k = hashToInt(N.toUnsignedBytes(padLen), g.toUnsignedBytes(padLen))

        // S = (B - k * g^x mod N)^(a + u*x) mod N
        val gxModN = g.modPow(x, N)
        val base = (serverEphemeralB - (k * gxModN).mod(N)).mod(N)
        val exponent = a + (u * x)
        val S = base.modPow(exponent, N)
        val K = sha512(S.toUnsignedBytes(padLen))

        val M1 = computeM1(N, g, username, salt, A, serverEphemeralB, K, padLen)
        val expectedM2 = sha512(A.toUnsignedBytes(padLen), M1, K)

        return SrpProof(
            clientEphemeralA = A,
            clientProofM1 = M1,
            expectedServerProofM2 = expectedM2,
            sharedKeyK = K
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

    private fun hashToInt(vararg parts: ByteArray): BigInteger =
        sha512(*parts).toUnsignedBigInteger()

    private fun computeM1(
        N: BigInteger,
        g: BigInteger,
        username: String,
        salt: ByteArray,
        A: BigInteger,
        B: BigInteger,
        K: ByteArray,
        padLen: Int
    ): ByteArray {
        val hN = sha512(N.toUnsignedBytes(padLen))
        val hg = sha512(g.toUnsignedBytes(padLen))
        val hNxorHg = ByteArray(hN.size) { i -> (hN[i].toInt() xor hg[i].toInt()).toByte() }
        val hI = sha512(username.toByteArray(Charsets.UTF_8))
        return sha512(
            hNxorHg,
            hI,
            salt,
            A.toUnsignedBytes(padLen),
            B.toUnsignedBytes(padLen),
            K
        )
    }

    companion object {
        /** Proton SRP fixed generator. */
        val G_DEFAULT: BigInteger = BigInteger.TWO

        /**
         * `[A]` Proton appears to use a fixed identity in the SRP mix — the
         * @protontech/crypto source needs to confirm whether it's the user's
         * email, a literal "proton", or empty. Using empty here is a safe
         * default for RFC 5054 compatibility; the value is mixed into M1 only.
         */
        const val USERNAME_FIXED: String = ""
    }
}

data class SrpProof(
    val clientEphemeralA: BigInteger,
    val clientProofM1: ByteArray,
    val expectedServerProofM2: ByteArray,
    val sharedKeyK: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SrpProof) return false
        return clientEphemeralA == other.clientEphemeralA &&
            clientProofM1.contentEquals(other.clientProofM1) &&
            expectedServerProofM2.contentEquals(other.expectedServerProofM2) &&
            sharedKeyK.contentEquals(other.sharedKeyK)
    }

    override fun hashCode(): Int {
        var result = clientEphemeralA.hashCode()
        result = 31 * result + clientProofM1.contentHashCode()
        result = 31 * result + expectedServerProofM2.contentHashCode()
        result = 31 * result + sharedKeyK.contentHashCode()
        return result
    }
}
