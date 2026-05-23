// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.sync.auth

import io.pcontacts.core.crypto.srp.SrpClient
import io.pcontacts.core.logging.Logger
import io.pcontacts.core.logging.NoOpSink
import io.pcontacts.core.logging.RedactingLogger
import io.pcontacts.core.proton.api.InMemorySession
import io.pcontacts.core.proton.api.auth.AuthRequest
import io.pcontacts.core.proton.api.auth.AuthResponse
import io.pcontacts.core.proton.api.auth.InfoRequest
import io.pcontacts.core.proton.api.auth.ProtonAuthApi
import java.math.BigInteger
import java.util.Base64

/**
 * Coordinates one SRP login attempt end-to-end: fetch `auth/info`,
 * derive `x`, run client-side SRP, post `auth`, validate `ServerProof`,
 * persist tokens, and signal whether a 2FA challenge is outstanding.
 *
 * Inputs are intentionally injectable so unit tests can:
 *   - point `api` at MockWebServer
 *   - hand `srp` a deterministically seeded `SecureRandom`
 *   - swap in `InMemorySecretStore` / `InMemorySession`
 *   - bypass server-proof verification with `serverProofVerifier`
 *     when the test would otherwise have to mirror the entire SRP
 *     server-side computation
 *
 * Verification markers across this file:
 *   `[V]` endpoint paths, DTO shapes, two-factor bit semantics.
 *   `[A]` SRP `x` derivation (see SrpXDerivation), modulus arrives as raw
 *         base64 (real Proton ships an OpenPGP-signed cleartext envelope —
 *         decoder + ADR-0014 signature pinning land in a follow-up).
 *   `[U]` ChallengePayload — sent empty for now; if Proton's anti-bot layer
 *         rejects empty payloads this surfaces immediately at integration time.
 */
class SrpLoginOrchestrator(
    private val api: ProtonAuthApi,
    private val srp: SrpClient,
    private val secretStore: io.pcontacts.core.storage.SecretStore,
    private val session: InMemorySession,
    /**
     * Hook for tests. Production code uses the SrpClient-backed
     * constant-time verifier. Tests that don't mirror the full SRP server
     * pass `{ _, _ -> true }`.
     */
    private val serverProofVerifier: (server: ByteArray, expected: ByteArray) -> Boolean = srp::verifyServerProof,
    private val logger: Logger = RedactingLogger(tag = "SrpLogin", sink = NoOpSink)
) {

    suspend fun login(username: String, password: CharArray): LoginResult {
        logger.info { "login: getInfo user=<redacted>" }

        val info = try {
            api.getInfo(InfoRequest(username = username))
        } catch (t: Throwable) {
            logger.error(t) { "getInfo failed" }
            return LoginResult.Failed(reason = "info_failed")
        }

        val saltBytes = Base64.getDecoder().decode(info.salt)
        val saltB64Padded = Base64.getEncoder().encodeToString(padOrTruncate(saltBytes, BCRYPT_SALT_BYTES))

        val x = SrpXDerivation.deriveX(password, saltB64Padded)

        // [A] modulus is currently treated as raw base64; OpenPGP-envelope
        //     decoding + signature verification land with ADR-0014 pinning.
        val nBytes = Base64.getDecoder().decode(info.modulus)
        val n = BigInteger(1, nBytes)
        val bBytes = Base64.getDecoder().decode(info.serverEphemeral)
        val b = BigInteger(1, bBytes)
        val padLen = (n.bitLength() + 7) / 8

        val proof = try {
            srp.login(
                N = n,
                salt = saltBytes,
                serverEphemeralB = b,
                x = x
            )
        } catch (t: Throwable) {
            logger.error(t) { "srp client computation failed" }
            return LoginResult.Failed(reason = "srp_failed")
        }

        val authResp: AuthResponse = try {
            api.auth(
                AuthRequest(
                    username = username,
                    clientEphemeral = Base64.getEncoder().encodeToString(unsignedBytes(proof.clientEphemeralA, padLen)),
                    clientProof = Base64.getEncoder().encodeToString(proof.clientProofM1),
                    srpSession = info.srpSession,
                    payload = emptyMap()    // [U] ChallengePayload
                )
            )
        } catch (t: Throwable) {
            logger.error(t) { "auth call failed" }
            return LoginResult.Failed(reason = "auth_failed")
        }

        val serverProof = try {
            Base64.getDecoder().decode(authResp.serverProof)
        } catch (t: Throwable) {
            logger.error(t) { "server proof base64 decode failed" }
            return LoginResult.Failed(reason = "server_proof_decode_failed", uid = authResp.uid)
        }

        if (!serverProofVerifier(serverProof, proof.expectedServerProofM2)) {
            logger.warn { "server proof mismatch — possible MITM, aborting login" }
            return LoginResult.Failed(reason = "server_proof_mismatch", uid = authResp.uid)
        }

        // Persist + propagate to the live HTTP Session.
        secretStore.setUid(authResp.uid)
        secretStore.setAccessToken(authResp.accessToken)
        secretStore.setRefreshToken(authResp.refreshToken)
        session.update(uid = authResp.uid, accessToken = authResp.accessToken)

        // [V] TwoFactor bit semantics from packages/shared/lib/authentication/twoFactor.ts.
        return if (authResp.twoFactor and TWO_FACTOR_TOTP_BIT != 0) {
            LoginResult.TwoFactorRequired(uid = authResp.uid)
        } else {
            LoginResult.Success(uid = authResp.uid)
        }
    }

    private fun unsignedBytes(value: BigInteger, length: Int): ByteArray {
        val raw = value.toByteArray()
        val stripped = if (raw.isNotEmpty() && raw[0] == 0.toByte() && raw.size > 1) {
            raw.copyOfRange(1, raw.size)
        } else {
            raw
        }
        if (stripped.size == length) return stripped
        require(stripped.size <= length) { "value does not fit in $length bytes" }
        val padded = ByteArray(length)
        System.arraycopy(stripped, 0, padded, length - stripped.size, stripped.size)
        return padded
    }

    private fun padOrTruncate(input: ByteArray, length: Int): ByteArray {
        if (input.size == length) return input
        if (input.size > length) return input.copyOfRange(0, length)
        val out = ByteArray(length)
        System.arraycopy(input, 0, out, 0, input.size)
        return out
    }

    private companion object {
        const val BCRYPT_SALT_BYTES = 16
        const val TWO_FACTOR_TOTP_BIT = 1
    }
}
