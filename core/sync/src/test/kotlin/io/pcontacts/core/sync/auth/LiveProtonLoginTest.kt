// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.sync.auth

import io.pcontacts.core.crypto.srp.SrpClient
import io.pcontacts.core.proton.api.InMemorySession
import io.pcontacts.core.proton.api.ProtonApiConfig
import io.pcontacts.core.proton.api.retrofit.ProtonApiFactory
import io.pcontacts.core.storage.InMemorySecretStore
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.security.SecureRandom

/**
 * Live integration test against the real Proton API.
 *
 * Skipped by default — runs only when the env var
 * `PCONTACTS_LIVE_TEST` is set to `true`.
 *
 * This test validates:
 *   - auth/info DTO shape (salt, modulus, serverEphemeral, srpSession)
 *   - Modulus OpenPGP envelope parsing + signature verification against
 *     the pinned key
 *   - SRP hashPassword v4 derivation + proof generation
 *   - Little-endian byte encoding for all SRP BigInteger values
 *   - Server accepts the SRP proof (ServerProof round-trip)
 *   - auth response DTO shape (tokens, UID, twoFactor)
 *   - If login succeeds: /users + /keys/salts DTO shapes
 *   - ChallengePayload empty-map acceptance
 *
 * On success, flips remaining [A]/[U] markers to [V].
 */
class LiveProtonLoginTest {

    @Test fun live_login_against_proton_api() = runBlocking {
        assumeTrue("Set env PCONTACTS_LIVE_TEST=true", System.getenv("PCONTACTS_LIVE_TEST") == "true")

        val username = "pc0ntact@proton.me"
        val password = "Ornitorinco".toCharArray()

        val secretStore = InMemorySecretStore()
        val session = InMemorySession()

        val apiFactory = ProtonApiFactory(
            config = ProtonApiConfig(),
            session = session
        )

        val orchestrator = SrpLoginOrchestrator(
            api = apiFactory.auth,
            usersApi = apiFactory.users,
            srp = SrpClient(random = SecureRandom()),
            secretStore = secretStore,
            session = session
        )

        println("=== LiveProtonLoginTest ===")
        println("Target: ${ProtonApiConfig().baseUrl}")
        println("Username: $username")

        val result = orchestrator.login(username, password)

        println("Login result: $result")

        when (result) {
            is LoginResult.Success -> {
                println("SUCCESS — full SRP handshake accepted")
                println("  UID: ${result.uid}")
                println("  accessToken stored: ${secretStore.accessToken() != null}")
                println("  refreshToken stored: ${secretStore.refreshToken() != null}")
                println("  keyPassword stored: ${secretStore.keyPassword() != null}")

                try {
                    apiFactory.auth.revoke()
                    println("  logout: OK")
                } catch (t: Throwable) {
                    println("  logout: ${t.message}")
                }
            }
            is LoginResult.TwoFactorRequired -> {
                println("TWO_FACTOR_REQUIRED — SRP handshake accepted, 2FA gate hit")
                println("  UID: ${result.uid}")
                println("  accessToken stored: ${secretStore.accessToken() != null}")
                println("  This still validates: modulus sig, SRP math, DTO shapes, ChallengePayload")

                try {
                    apiFactory.auth.revoke()
                    println("  logout: OK")
                } catch (t: Throwable) {
                    println("  logout: ${t.message}")
                }
            }
            is LoginResult.Failed -> {
                println("FAILED — reason: ${result.reason}")
                println("  This indicates a bug in our implementation.")
                when (result.reason) {
                    "info_failed" -> println("  -> auth/info endpoint issue (network, DTO mismatch, or DNS guard)")
                    "modulus_signature_invalid" -> println("  -> pinned key doesn't match Proton's actual signing key")
                    "modulus_pin_missing" -> println("  -> pinned key resource failed to load from classpath")
                    "srp_failed" -> println("  -> SRP client computation threw (x derivation or proof math)")
                    "auth_failed" -> println("  -> POST auth rejected (wrong proof, DTO mismatch, or ChallengePayload)")
                    "server_proof_mismatch" -> println("  -> server's M2 doesn't match our expectation (SRP math divergence)")
                    "server_proof_decode_failed" -> println("  -> ServerProof field missing or not base64")
                    else -> println("  -> unexpected reason")
                }
                throw AssertionError("Live login failed: ${result.reason}")
            }
        }
    }
}
