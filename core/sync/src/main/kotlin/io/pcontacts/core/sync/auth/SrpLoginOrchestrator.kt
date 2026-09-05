// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.sync.auth

import io.pcontacts.core.crypto.bcrypt.ComputeKeyPassword
import io.pcontacts.core.crypto.srp.BouncyCastleProtonModulusVerifier
import io.pcontacts.core.crypto.srp.ProtonModulusEnvelope
import io.pcontacts.core.crypto.srp.ProtonModulusVerification
import io.pcontacts.core.crypto.srp.ProtonModulusVerifier
import io.pcontacts.core.crypto.srp.SrpClient
import io.pcontacts.core.crypto.srp.SrpProof
import io.pcontacts.core.logging.Logger
import io.pcontacts.core.logging.NoOpSink
import io.pcontacts.core.logging.RedactingLogger
import io.pcontacts.core.proton.api.InMemorySession
import io.pcontacts.core.proton.api.auth.AuthRequest
import io.pcontacts.core.proton.api.auth.AuthResponse
import io.pcontacts.core.proton.api.auth.InfoRequest
import io.pcontacts.core.proton.api.auth.InfoResponse
import io.pcontacts.core.proton.api.auth.ProtonAuthApi
import io.pcontacts.core.proton.api.auth.TwoFactorRequest
import io.pcontacts.core.proton.api.http.HumanVerificationRequiredException
import io.pcontacts.core.proton.api.httpStatusCode
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
 *   `[V]` endpoint paths, DTO shapes, two-factor bit semantics,
 *         SRP `x` derivation, ChallengePayload (empty map accepted),
 *         modulus OpenPGP envelope + signature verification.
 *         All validated against live Proton API on 2026-05-24.
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

    @Volatile private var lastUsername: String? = null

    /**
     * `/auth`'s access token carries `scope=self` only when 2FA is required;
     * `scope=full` (needed for `/users`) lands only after `/auth/2fa` succeeds.
     * So when 2FA is required we stash a private copy of the user's password,
     * defer the keyPassword derivation, and finish it inside
     * `submitTwoFactorCode` — at which point the scope has been promoted and
     * `/users` no longer returns 403.
     *
     * The stash is zeroed on success, on 2FA rejection, and on any new
     * `login()` attempt. It is the only place the password lingers past
     * `login()` returning; on process death the GC drops it without zeroing
     * (acceptable — process memory is wiped).
     */
    @Volatile private var pendingTwoFactorPassword: CharArray? = null

    private sealed interface Step<out T> {
        data class Ok<T>(val value: T) : Step<T>

        // Carries any terminal LoginResult — Failed, HumanVerificationRequired,
        // or any future early-exit variant — so callers can bubble it up via
        // `orReturn { return it }` from `loginInternal`.
        data class Abort(val result: LoginResult) : Step<Nothing>
    }

    private inline fun <T> Step<T>.orReturn(block: (LoginResult) -> Nothing): T = when (this) {
        is Step.Ok -> value
        is Step.Abort -> block(result)
    }

    private data class VerifiedModulus(val n: BigInteger, val nBytesLE: ByteArray, val padLen: Int)

    suspend fun login(username: String, password: CharArray): LoginResult = try {
        loginInternal(username, password)
    } finally {
        password.fill('\u0000')
    }

    // Six returns mirror the six SRP phases (info, modulus, proof, auth,
    // 2FA branch, key-derivation). Collapsing them buries the protocol shape.
    @Suppress("ReturnCount")
    private suspend fun loginInternal(username: String, password: CharArray): LoginResult {
        logger.info { "login: getInfo user=<redacted>" }
        lastUsername = username
        clearPendingTwoFactorPassword()   // drop any stash from a prior attempt

        val info = fetchInfo(username).orReturn { return it }
        val mod = decodeAndVerifyModulus(info).orReturn { return it }
        val proof = computeSrpProof(password, info, mod).orReturn { return it }
        val authResp = submitAuthAndVerifyProof(username, info, proof, mod.padLen).orReturn { return it }

        secretStore.setUid(authResp.uid)
        secretStore.setAccessToken(authResp.accessToken)
        secretStore.setRefreshToken(authResp.refreshToken)
        session.update(uid = authResp.uid, accessToken = authResp.accessToken)

        // [V] TwoFactor bit semantics from packages/shared/lib/authentication/twoFactor.ts.
        val needsTwoFactor = authResp.twoFactor and TWO_FACTOR_TOTP_BIT != 0
        if (needsTwoFactor) {
            // Stash a private copy of the password; submitTwoFactorCode will
            // consume it to finish keyPassword derivation once /auth/2fa has
            // promoted the access token from scope=self to scope=full.
            pendingTwoFactorPassword = password.copyOf()
            return LoginResult.TwoFactorRequired(uid = authResp.uid, username = username)
        }

        // No 2FA — access token already carries scope=full; derive now.
        return finishKeyDerivation(password, authResp.uid, username)
    }

    /**
     * Runs `/users` + `/keys/salts`, computes bcrypt-SHA512(password, salt),
     * and persists the result. Maps all failure modes to a typed
     * `LoginResult`. On non-HV failure also clears the half-written session
     * tokens so the next sync doesn't fire a misleading KEY_PASSWORD_MISSING
     * notification.
     */
    private suspend fun finishKeyDerivation(
        password: CharArray,
        uid: String,
        username: String
    ): LoginResult = try {
        deriveAndPersistKeyPassword(password)
        LoginResult.Success(uid = uid, username = username)
    } catch (e: HumanVerificationRequiredException) {
        logger.warn { "key-derivation step returned 9001 — human verification required" }
        LoginResult.HumanVerificationRequired(
            verificationUrl = e.verificationUrl,
            uid = uid,
            username = username
        )
    } catch (t: Throwable) {
        val code = t.httpStatusCode()
        logger.error(t) { "key-derivation step failed http=$code" }
        secretStore.setUid(null)
        secretStore.setAccessToken(null)
        secretStore.setRefreshToken(null)
        session.update(uid = null, accessToken = null)
        LoginResult.Failed(
            reason = "key_derivation_failed",
            uid = uid,
            username = username
        )
    }

    private fun clearPendingTwoFactorPassword() {
        pendingTwoFactorPassword?.fill('\u0000')
        pendingTwoFactorPassword = null
    }

    /**
     * An HTTP status means Proton answered — a rejection, never a
     * connectivity problem; only a transport failure (no status) may
     * surface as one. On 401 the auth session itself is gone (`[A]`
     * e.g. the attempt limit) so retrying this session cannot succeed:
     * fail closed to a fresh sign-in and drop the stashed password.
     * Other 4xx keep the stash so the user can retry on the same
     * screen (`[A]` 422 Code 8002 observed for a wrong/expired code).
     */
    private fun classifyTwoFactorFailure(
        t: Throwable,
        uid: String,
        username: String
    ): LoginResult.Failed {
        val httpCode = t.httpStatusCode()
        val reason = when {
            httpCode == null -> {
                logger.error(t) { "auth2FA call failed (transport)" }
                "two_factor_failed"
            }
            httpCode == 401 -> {
                logger.warn { "auth2FA returned HTTP 401 — session invalidated" }
                clearPendingTwoFactorPassword()
                "no_session"
            }
            httpCode < 500 -> {
                logger.warn { "auth2FA rejected — HTTP $httpCode" }
                "two_factor_rejected"
            }
            else -> {
                logger.warn { "auth2FA server error — HTTP $httpCode" }
                "two_factor_server_error"
            }
        }
        return LoginResult.Failed(reason = reason, uid = uid, username = username)
    }

    private suspend fun fetchInfo(username: String): Step<InfoResponse> = try {
        Step.Ok(api.getInfo(InfoRequest(username = username)))
    } catch (e: HumanVerificationRequiredException) {
        // 9001 on /auth/info — the pre-session captcha path Proton uses on
        // fresh IPs. Surface as HV; the UI launches the captcha then retries.
        logger.warn { "auth/info returned 9001 — human verification required" }
        Step.Abort(LoginResult.HumanVerificationRequired(verificationUrl = e.verificationUrl))
    } catch (t: Throwable) {
        // [V] HTTP 400/422 from auth/info indicate x-pm-appversion rejection
        // (custom client ID or version below the acceptance window). Distinct
        // from network/parse errors so the UI can surface "app may be outdated".
        val httpCode = t.httpStatusCode()
        if (httpCode == 400 || httpCode == 422) {
            logger.warn { "auth/info returned HTTP $httpCode — likely x-pm-appversion rejected" }
            Step.Abort(LoginResult.Failed(reason = "appversion_rejected"))
        } else {
            logger.error(t) { "getInfo failed" }
            Step.Abort(LoginResult.Failed(reason = "info_failed"))
        }
    }

    private fun decodeAndVerifyModulus(info: InfoResponse): Step<VerifiedModulus> {
        val decoded = ProtonModulusEnvelope.decode(info.modulus)
        val armoredSig = decoded.armoredSignature
            ?: run {
                logger.warn { "modulus arrived without an OpenPGP envelope — aborting (ADR-0014)" }
                return Step.Abort(LoginResult.Failed(reason = "modulus_unsigned"))
            }

        when (modulusVerifier.verify(decoded.cleartextBase64, armoredSig)) {
            ProtonModulusVerification.VALID -> { /* proceed */ }
            ProtonModulusVerification.INVALID -> {
                logger.warn { "modulus signature INVALID — treating as MITM, aborting login" }
                return Step.Abort(LoginResult.Failed(reason = "modulus_signature_invalid"))
            }
            ProtonModulusVerification.NO_SIGNER_KEY -> {
                logger.warn { "modulus signature NOT verified — pinned Proton SRP key failed to load (ADR-0014)" }
                return Step.Abort(LoginResult.Failed(reason = "modulus_pin_missing"))
            }
        }

        // [V] Proton's API sends BigInteger values in little-endian byte
        // order (go-srp's fromNat/toNat convention). Reverse before
        // constructing BigIntegers; pass raw LE bytes to hashPassword.
        val nBytesLE = Base64.getDecoder().decode(decoded.cleartextBase64)
        val n = BigInteger(1, nBytesLE.reversedArray())
        return Step.Ok(VerifiedModulus(n = n, nBytesLE = nBytesLE, padLen = (n.bitLength() + 7) / 8))
    }

    private fun computeSrpProof(
        password: CharArray,
        info: InfoResponse,
        mod: VerifiedModulus
    ): Step<SrpProof> {
        // [V] SRP x derivation receives the raw LE modulus bytes — go-srp's
        // hashPassword uses them as-is (no reversal).
        val x = SrpXDerivation.deriveX(password, info.salt, mod.nBytesLE)
        val bBytesLE = Base64.getDecoder().decode(info.serverEphemeral)
        val b = BigInteger(1, bBytesLE.reversedArray())

        return try {
            Step.Ok(srp.login(N = mod.n, serverEphemeralB = b, x = x))
        } catch (t: Throwable) {
            logger.error(t) { "srp client computation failed" }
            Step.Abort(LoginResult.Failed(reason = "srp_failed"))
        }
    }

    private suspend fun submitAuthAndVerifyProof(
        username: String,
        info: InfoResponse,
        proof: SrpProof,
        padLen: Int
    ): Step<AuthResponse> {
        val authResp = try {
            api.auth(
                AuthRequest(
                    username = username,
                    clientEphemeral = Base64.getEncoder().encodeToString(toLittleEndianBytes(proof.clientEphemeralA, padLen)),
                    clientProof = Base64.getEncoder().encodeToString(proof.clientProofM1),
                    srpSession = info.srpSession,
                    payload = emptyMap()    // [V] ChallengePayload — empty map accepted (2026-05-24)
                )
            )
        } catch (e: HumanVerificationRequiredException) {
            // [V] Proton returns Code:9001 on `/auth` before issuing the 2FA
            // challenge when the IP/session lacks human-verification proof
            // (WebClients `withApiHandlers.ts` shows the captcha modal, then
            // retries the same `/auth` with the `x-pm-human-verification-token*`
            // headers; on success the response carries `TwoFactor:1` and the
            // 2FA prompt follows). We surface the requirement so the Login UI
            // can launch the captcha Custom Tab and re-invoke login() after.
            logger.warn { "auth returned 9001 — human verification required" }
            return Step.Abort(LoginResult.HumanVerificationRequired(verificationUrl = e.verificationUrl))
        } catch (t: Throwable) {
            val code = t.httpStatusCode()
            logger.error(t) { "auth call failed http=$code" }
            return Step.Abort(LoginResult.Failed(reason = "auth_failed"))
        }

        val serverProof = try {
            Base64.getDecoder().decode(authResp.serverProof)
        } catch (t: Throwable) {
            logger.error(t) { "server proof base64 decode failed" }
            return Step.Abort(LoginResult.Failed(reason = "server_proof_decode_failed", uid = authResp.uid))
        }

        if (!serverProofVerifier(serverProof, proof.expectedServerProofM2)) {
            logger.warn { "server proof mismatch — possible MITM, aborting login" }
            return Step.Abort(LoginResult.Failed(reason = "server_proof_mismatch", uid = authResp.uid))
        }

        return Step.Ok(authResp)
    }

    /**
     * Fetches User + KeySalts, computes `keyPassword = bcrypt-SHA-512(
     * password, primaryKeySalt)` (Plan §2.7 step 12), and stores the
     * bcrypt string bytes under the Keystore AEAD key (ADR-0009).
     *
     * `[V]` — the PGP key unlock with the bcrypt string itself
     * (matching the Proton web client's `decryptPrivateKey(armored,
     * keyPassword)` call); validated against live Proton account
     * (2026-05-24): key unlocks, cards decrypt + verify.
     */
    private suspend fun deriveAndPersistKeyPassword(password: CharArray) {
        val user = usersApi.getUser().user
        val primary = user.keys.firstOrNull { it.primary == 1 && it.active == 1 }
            ?: error("no active primary key in /users")

        val saltDto = usersApi.getKeySalts().keySalts
            .firstOrNull { it.keyId == primary.id }
            ?: error("no /keys/salts entry for primary key id (hash-redacted)")
        val saltB64 = saltDto.keySalt
            ?: error("primary key has null KeySalt — key activation pending")

        val bcryptString = ComputeKeyPassword.derive(password, saltB64)
        secretStore.setKeyPassword(bcryptString.toByteArray(Charsets.UTF_8))
    }

    /**
     * Second stage of a 2FA login. Call this after `login()` returned
     * `TwoFactorRequired` and the user has entered their TOTP code.
     *
     * `[V]` 1000 is Proton's app-level success Code on 2xx responses.
     * HTTP rejections surface as Retrofit `HttpException` and are
     * classified: 401 → `no_session` (auth session invalidated), other
     * 4xx → `two_factor_rejected` (`[A]` 422 Code 8002 is the observed
     * wrong/expired-TOTP response; the warn log carries the live status
     * for validation), 5xx → `two_factor_server_error`. Only transport
     * failures (no HTTP response at all) map to `two_factor_failed` —
     * the UI renders that one as a connectivity problem.
     */
    // Six returns mirror the six failure modes of /auth/2fa (no session,
    // human-verification, transport/HTTP classification, server reject,
    // missing stash, and the final key-derivation hand-off). Collapsing
    // them obscures which path the caller hit.
    @Suppress("ReturnCount")
    suspend fun submitTwoFactorCode(code: String): LoginResult {
        val uid = session.uid()
        if (uid.isNullOrBlank()) {
            logger.warn { "submitTwoFactorCode called without a live session" }
            return LoginResult.Failed(reason = "no_session")
        }

        val username = lastUsername ?: ""

        val response = try {
            api.auth2FA(TwoFactorRequest(twoFactorCode = code))
        } catch (e: HumanVerificationRequiredException) {
            // 9001 between SRP and TOTP — Proton can re-challenge if IP
            // reputation degrades mid-session. UI surfaces captcha; the
            // existing 2FA UID stays in `session` so the retried /auth/2fa
            // (after captcha) still carries x-pm-uid + Authorization.
            logger.warn { "auth2FA returned 9001 — human verification required" }
            return LoginResult.HumanVerificationRequired(
                verificationUrl = e.verificationUrl,
                uid = uid,
                username = username
            )
        } catch (t: Throwable) {
            return classifyTwoFactorFailure(t, uid, username)
        }

        if (response.code != PROTON_SUCCESS_CODE) {
            logger.warn { "auth2FA returned non-success Code=${response.code}" }
            clearPendingTwoFactorPassword()
            return LoginResult.Failed(reason = "two_factor_rejected", uid = uid, username = username)
        }

        // 2FA accepted — the access token has been promoted from scope=self
        // to scope=full, so /users and /keys/salts are now reachable. Finish
        // the keyPassword derivation deferred by loginInternal.
        val pending = pendingTwoFactorPassword
        pendingTwoFactorPassword = null
        if (pending == null) {
            logger.warn { "submitTwoFactorCode success but no stashed password — login flow corrupted" }
            return LoginResult.Failed(reason = "unexpected_state", uid = uid, username = username)
        }
        return try {
            finishKeyDerivation(pending, uid, username)
        } finally {
            pending.fill(Char(0))
        }
    }

    /**
     * `[V]` Proton SRP uses little-endian encoding for BigInteger values
     * on the wire (go-srp's `fromNat`). Big-endian pad, then reverse.
     */
    private fun toLittleEndianBytes(value: BigInteger, length: Int): ByteArray {
        val raw = value.toByteArray()
        val stripped = if (raw.isNotEmpty() && raw[0] == 0.toByte() && raw.size > 1) {
            raw.copyOfRange(1, raw.size)
        } else {
            raw
        }
        require(stripped.size <= length) { "value does not fit in $length bytes" }
        val padded = ByteArray(length)
        System.arraycopy(stripped, 0, padded, length - stripped.size, stripped.size)
        padded.reverse()
        return padded
    }

    private companion object {
        const val TWO_FACTOR_TOTP_BIT = 1
        const val PROTON_SUCCESS_CODE = 1000
    }
}
