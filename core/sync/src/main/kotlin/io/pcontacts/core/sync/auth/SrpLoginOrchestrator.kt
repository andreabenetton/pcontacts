// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.sync.auth

import io.pcontacts.core.crypto.bcrypt.ComputeKeyPassword
import io.pcontacts.core.crypto.srp.BouncyCastleProtonModulusVerifier
import io.pcontacts.core.crypto.srp.ProtonModulusEnvelope
import io.pcontacts.core.crypto.srp.ProtonModulusVerification
import io.pcontacts.core.crypto.srp.ProtonModulusVerifier
import io.pcontacts.core.crypto.srp.SrpClient
import io.pcontacts.core.logging.Logger
import io.pcontacts.core.logging.NoOpSink
import io.pcontacts.core.logging.RedactingLogger
import io.pcontacts.core.proton.api.InMemorySession
import io.pcontacts.core.proton.api.auth.AuthRequest
import io.pcontacts.core.proton.api.auth.AuthResponse
import io.pcontacts.core.proton.api.auth.InfoRequest
import io.pcontacts.core.proton.api.auth.ProtonAuthApi
import io.pcontacts.core.proton.api.auth.TwoFactorRequest
import io.pcontacts.core.proton.api.users.ProtonUsersApi
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
    private val usersApi: ProtonUsersApi,
    private val srp: SrpClient,
    private val secretStore: io.pcontacts.core.storage.SecretStore,
    private val session: InMemorySession,
    /**
     * Hook for tests. Production code uses the SrpClient-backed
     * constant-time verifier. Tests that don't mirror the full SRP server
     * pass `{ _, _ -> true }`.
     */
    private val serverProofVerifier: (server: ByteArray, expected: ByteArray) -> Boolean = srp::verifyServerProof,
    /**
     * ADR-0014 — verifies the OpenPGP detached signature on the SRP
     * Modulus. Default loads Proton's pinned public key from the
     * classpath (`proton_srp_signing_key.asc`). On `NO_SIGNER_KEY`
     * (resource missing or unparseable), login aborts — the key is
     * committed and must load successfully in production builds.
     * Tests inject `NoOpProtonModulusVerifier`.
     */
    private val modulusVerifier: ProtonModulusVerifier = BouncyCastleProtonModulusVerifier(
        pinnedPublicKeyArmored = BouncyCastleProtonModulusVerifier.loadPinnedKeyFromClasspath()
    ),
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

        // Real Proton ships Modulus as an OpenPGP cleartext-signed envelope;
        // peel off the envelope here, then verify the detached signature
        // against Proton's pinned SRP signing public key (ADR-0014).
        val modulusDecoded = ProtonModulusEnvelope.decode(info.modulus)
        val armoredSig = modulusDecoded.armoredSignature
        if (armoredSig != null) {
            when (modulusVerifier.verify(modulusDecoded.cleartextBase64, armoredSig)) {
                ProtonModulusVerification.VALID -> {
                    // proceed
                }
                ProtonModulusVerification.INVALID -> {
                    logger.warn { "modulus signature INVALID — treating as MITM, aborting login" }
                    return LoginResult.Failed(reason = "modulus_signature_invalid")
                }
                ProtonModulusVerification.NO_SIGNER_KEY -> {
                    // ADR-0014 production gate: the pinned key resource
                    // is now committed (proton_srp_signing_key.asc). If
                    // it fails to load, the build or classpath is broken
                    // — abort rather than silently skipping verification.
                    logger.warn { "modulus signature NOT verified — pinned Proton SRP key failed to load (ADR-0014)" }
                    return LoginResult.Failed(reason = "modulus_pin_missing")
                }
            }
        } else {
            logger.warn { "modulus arrived without an OpenPGP envelope — verification cannot run" }
        }
        val nBytes = Base64.getDecoder().decode(modulusDecoded.cleartextBase64)
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

        // Plan §2.7 steps 11-13: with the session live, derive and persist
        // the keyPassword. Sync needs it to unlock User.Keys[0] later.
        // Failure here doesn't abort login — the user can still finish
        // 2FA / land on the home screen — but any decrypt-aware sync will
        // refuse to run until keyPassword is available (logged loudly).
        try {
            deriveAndPersistKeyPassword(password)
        } catch (t: Throwable) {
            logger.error(t) { "keyPassword derivation failed; sync will require re-login" }
        }

        // [V] TwoFactor bit semantics from packages/shared/lib/authentication/twoFactor.ts.
        return if (authResp.twoFactor and TWO_FACTOR_TOTP_BIT != 0) {
            LoginResult.TwoFactorRequired(uid = authResp.uid)
        } else {
            LoginResult.Success(uid = authResp.uid)
        }
    }

    /**
     * Fetches User + KeySalts, computes `keyPassword = bcrypt-SHA-512(
     * password, primaryKeySalt)` (Plan §2.7 step 12), and stores the
     * bcrypt string bytes under the Keystore AEAD key (ADR-0009).
     *
     * `[A]` — the PGP key is unlocked with the bcrypt string itself
     * (matching the Proton web client's `decryptPrivateKey(armored,
     * keyPassword)` call); ADR-0013 vectors will flip this to `[V]`.
     */
    private suspend fun deriveAndPersistKeyPassword(password: CharArray) {
        val user = usersApi.getUser().user
        val primary = user.keys.firstOrNull { it.primary == 1 && it.active == 1 }
            ?: throw IllegalStateException("no active primary key in /users")

        val saltDto = usersApi.getKeySalts().keySalts
            .firstOrNull { it.keyId == primary.id }
            ?: throw IllegalStateException("no /keys/salts entry for primary key id (hash-redacted)")
        val saltB64 = saltDto.keySalt
            ?: throw IllegalStateException("primary key has null KeySalt — key activation pending")

        val bcryptString = ComputeKeyPassword.derive(password, saltB64)
        secretStore.setKeyPassword(bcryptString.toByteArray(Charsets.UTF_8))
    }

    /**
     * Second stage of a 2FA login. Call this after `login()` returned
     * `TwoFactorRequired` and the user has entered their TOTP code.
     *
     * Preconditions:
     *   - `login()` succeeded recently; the access token and UID it
     *     persisted are still live (Proton's 2FA window is short — minutes,
     *     not hours [A]).
     *   - The `Session` carried into this orchestrator instance still
     *     holds the post-SRP uid + access token; the OkHttp interceptor
     *     stack relies on those to attach `x-pm-uid` and `Authorization`.
     *
     * `[V]` 1000 is Proton's app-level success Code on 2xx responses
     * (`packages/shared/lib/api/helpers/apiErrorHelper.ts` treats anything
     * else as a recoverable error). HTTP 422 / 401 surface as Retrofit
     * `HttpException` and map to `two_factor_failed`.
     */
    suspend fun submitTwoFactorCode(code: String): LoginResult {
        val uid = session.uid()
        if (uid.isNullOrBlank()) {
            logger.warn { "submitTwoFactorCode called without a live session" }
            return LoginResult.Failed(reason = "no_session")
        }

        val response = try {
            api.auth2FA(TwoFactorRequest(twoFactorCode = code))
        } catch (t: Throwable) {
            // Includes HttpException for 401/422 (wrong code, expired window)
            // and IOException for network errors. Both collapse to a single
            // non-sensitive reason; the user retypes the code or restarts login.
            logger.error(t) { "auth2FA call failed" }
            return LoginResult.Failed(reason = "two_factor_failed", uid = uid)
        }

        if (response.code != PROTON_SUCCESS_CODE) {
            logger.warn { "auth2FA returned non-success Code=${response.code}" }
            return LoginResult.Failed(reason = "two_factor_rejected", uid = uid)
        }

        return LoginResult.Success(uid = uid)
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
        const val PROTON_SUCCESS_CODE = 1000
    }
}
