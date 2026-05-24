// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.sync.auth

import io.pcontacts.core.crypto.srp.SrpClient
import io.pcontacts.core.proton.api.InMemorySession
import io.pcontacts.core.proton.api.ProtonApiConfig
import io.pcontacts.core.proton.api.retrofit.ProtonApiFactory
import io.pcontacts.core.storage.InMemorySecretStore
import java.math.BigInteger
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.QueueDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SrpLoginOrchestratorTest {

    // 1024-bit MODP group from RFC 3526 §2 — small enough for fast tests
    // yet still hits every code path the 2048-bit production modulus does.
    private val N1024 = BigInteger(
        "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E08" +
            "8A67CC74020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B" +
            "302B0A6DF25F14374FE1356D6D51C245E485B576625E7EC6F44C42E9" +
            "A637ED6B0BFF5CB6F406B7EDEE386BFB5A899FA5AE9F24117C4B1FE6" +
            "49286651ECE65381FFFFFFFFFFFFFFFF",
        16
    )

    private val saltBytes = ByteArray(16) { it.toByte() }
    private val serverEphemeralBytes = ByteArray(128) { ((it * 7) and 0xFF).toByte() }
    // Base64 of [0..15] — a valid 16-byte bcrypt salt.
    private val SAMPLE_SALT_B64 = "AAECAwQFBgcICQoLDA0ODw=="

    private lateinit var server: MockWebServer
    private lateinit var secretStore: InMemorySecretStore
    private lateinit var session: InMemorySession

    @Before fun setUp() {
        server = MockWebServer().apply {
            // Default MockWebServer queue blocks until a response is enqueued.
            // The orchestrator now makes optional post-auth /users + /keys/salts
            // calls; tests that don't drive the key-password path shouldn't
            // hang waiting for those — return a 404 by default so those calls
            // throw and are swallowed by the orchestrator's catch.
            dispatcher = QueueDispatcher().apply {
                setFailFast(MockResponse().setResponseCode(404))
            }
            start()
        }
        secretStore = InMemorySecretStore()
        session = InMemorySession()
    }

    @After fun tearDown() {
        server.shutdown()
    }

    @Test fun login_success_persists_tokens_and_propagates_session() = runTest {
        enqueueInfoResponse()
        enqueueAuthResponse(uid = "uid-success", twoFactor = 0)

        val result = newOrchestrator().login(username = "alice@proton.test", password = "p4ssw0rd".toCharArray())

        assertEquals(LoginResult.Success(uid = "uid-success"), result)
        assertEquals("uid-success", secretStore.uid())
        assertEquals("access-token-XYZ", secretStore.accessToken())
        assertEquals("refresh-token-XYZ", secretStore.refreshToken())
        assertEquals("uid-success", session.uid())
        assertEquals("access-token-XYZ", session.accessToken())
    }

    @Test fun login_with_two_factor_returns_TwoFactorRequired_and_still_persists() = runTest {
        enqueueInfoResponse()
        enqueueAuthResponse(uid = "uid-2fa", twoFactor = 1)

        val result = newOrchestrator().login("bob@proton.test", "x".toCharArray())

        assertEquals(LoginResult.TwoFactorRequired(uid = "uid-2fa"), result)
        // Tokens already land — the orchestrator is now in a state where
        // /core/v4/auth/2fa can be called with x-pm-uid + Authorization set.
        assertEquals("uid-2fa", secretStore.uid())
        assertNotNull(secretStore.accessToken())
    }

    @Test fun info_call_failure_yields_info_failed_and_persists_nothing() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("server error"))

        val result = newOrchestrator().login("u", "p".toCharArray())

        assertTrue("expected Failed(info_failed), was $result", result is LoginResult.Failed && (result as LoginResult.Failed).reason == "info_failed")
        assertNull(secretStore.uid())
        assertNull(secretStore.accessToken())
    }

    @Test fun server_proof_mismatch_aborts_and_does_not_persist() = runTest {
        enqueueInfoResponse()
        enqueueAuthResponse(uid = "uid-mismatch", twoFactor = 0)

        val orchestrator = SrpLoginOrchestrator(
            api = api(),
            usersApi = usersApi(),
            srp = SrpClient(random = seededRandom()),
            secretStore = secretStore,
            session = session,
            serverProofVerifier = { _, _ -> false }   // simulate MITM-style server-proof rejection
        )

        val result = orchestrator.login("u", "p".toCharArray())

        assertTrue("expected Failed(server_proof_mismatch), was $result",
            result is LoginResult.Failed && (result as LoginResult.Failed).reason == "server_proof_mismatch")
        // Persistence runs only AFTER the verifier accepts.
        assertNull(secretStore.uid())
        assertNull(secretStore.accessToken())
    }

    @Test fun outgoing_auth_body_carries_required_srp_fields() = runTest {
        enqueueInfoResponse()
        enqueueAuthResponse(uid = "uid-body", twoFactor = 0)

        newOrchestrator().login("alice@proton.test", "p4ssw0rd".toCharArray())

        server.takeRequest()   // /info
        val authReq = server.takeRequest()
        assertEquals("/core/v4/auth", authReq.path)
        val body = authReq.body.readUtf8()
        assertTrue("missing Username: $body", body.contains("\"Username\":\"alice@proton.test\""))
        assertTrue("missing ClientEphemeral", body.contains("\"ClientEphemeral\""))
        assertTrue("missing ClientProof", body.contains("\"ClientProof\""))
        assertTrue("missing SRPSession", body.contains("\"SRPSession\":\"session-id\""))
    }

    @Test fun submitTwoFactorCode_success_returns_Success_and_carries_session_headers() = runTest {
        // Bootstrap: full SRP login → TwoFactorRequired persists session.
        enqueueInfoResponse()
        enqueueAuthResponse(uid = "uid-2fa", twoFactor = 1)
        val orchestrator = newOrchestrator()
        val first = orchestrator.login("u", "p".toCharArray())
        assertTrue("expected TwoFactorRequired, was $first", first is LoginResult.TwoFactorRequired)
        server.takeRequest()    // /info
        server.takeRequest()    // /auth
        server.takeRequest()    // /users — best-effort key-password fetch, 404 by failFast

        // Now the TOTP submission.
        server.enqueue(MockResponse().setBody("""{"Code":1000,"Scopes":["self","full"]}"""))
        val second = orchestrator.submitTwoFactorCode("654321")

        assertEquals(LoginResult.Success(uid = "uid-2fa"), second)

        val recorded = server.takeRequest()
        assertEquals("/core/v4/auth/2fa", recorded.path)
        assertEquals("uid-2fa", recorded.getHeader("x-pm-uid"))
        assertEquals("Bearer access-token-XYZ", recorded.getHeader("Authorization"))
        assertTrue(recorded.body.readUtf8().contains("\"TwoFactorCode\":\"654321\""))
    }

    @Test fun submitTwoFactorCode_without_session_returns_no_session() = runTest {
        // No login() preceded; session is empty.
        val result = newOrchestrator().submitTwoFactorCode("000000")
        assertTrue("expected Failed(no_session), was $result",
            result is LoginResult.Failed && (result as LoginResult.Failed).reason == "no_session")
    }

    @Test fun submitTwoFactorCode_http_failure_returns_two_factor_failed() = runTest {
        enqueueInfoResponse()
        enqueueAuthResponse(uid = "uid-2fa-fail", twoFactor = 1)
        val orchestrator = newOrchestrator()
        orchestrator.login("u", "p".toCharArray())
        server.takeRequest(); server.takeRequest(); server.takeRequest()  // /info, /auth, /users (best-effort, 404)

        server.enqueue(MockResponse().setResponseCode(422).setBody("""{"Code":8002,"Error":"Invalid code"}"""))
        val result = orchestrator.submitTwoFactorCode("999999")

        assertTrue("expected Failed(two_factor_failed), was $result",
            result is LoginResult.Failed && (result as LoginResult.Failed).reason == "two_factor_failed")
        assertEquals("uid-2fa-fail", (result as LoginResult.Failed).uid)
    }

    @Test fun submitTwoFactorCode_non_success_code_returns_two_factor_rejected() = runTest {
        enqueueInfoResponse()
        enqueueAuthResponse(uid = "uid-2fa-reject", twoFactor = 1)
        val orchestrator = newOrchestrator()
        orchestrator.login("u", "p".toCharArray())
        server.takeRequest(); server.takeRequest(); server.takeRequest()  // /info, /auth, /users (best-effort, 404)

        // HTTP 200 but app-level Code is not 1000.
        server.enqueue(MockResponse().setBody("""{"Code":9001,"Scopes":[]}"""))
        val result = orchestrator.submitTwoFactorCode("111111")

        assertTrue("expected Failed(two_factor_rejected), was $result",
            result is LoginResult.Failed && (result as LoginResult.Failed).reason == "two_factor_rejected")
    }

    @Test fun login_success_derives_and_persists_keyPassword_from_primary_key_salt() = runTest {
        enqueueInfoResponse()
        enqueueAuthResponse(uid = "uid-key", twoFactor = 0)
        // Post-auth, the orchestrator fetches /users then /keys/salts.
        enqueueUserResponse(primaryKeyId = "kp-1")
        enqueueKeySaltsResponse(primaryKeyId = "kp-1", saltB64 = SAMPLE_SALT_B64)

        val result = newOrchestrator().login("u", "p4ssw0rd".toCharArray())

        assertEquals(LoginResult.Success(uid = "uid-key"), result)
        val stored = secretStore.keyPassword()
        assertNotNull("keyPassword must be persisted on successful login", stored)
        // ComputeKeyPassword.derive now returns the 31-char trailing hash
        // (matching Proton's computeKeyPassword output).
        assertEquals(
            "keyPassword must be 31 characters (bcrypt trailing hash)",
            31,
            String(stored!!, Charsets.UTF_8).length
        )
    }

    @Test fun login_success_when_users_endpoint_fails_still_succeeds_without_keyPassword() = runTest {
        enqueueInfoResponse()
        enqueueAuthResponse(uid = "uid-no-key", twoFactor = 0)
        // No /users response enqueued — MockWebServer's default dispatcher
        // returns 404 → Retrofit throws → orchestrator swallows.

        val result = newOrchestrator().login("u", "p".toCharArray())

        assertEquals(LoginResult.Success(uid = "uid-no-key"), result)
        // Tokens still persisted; keyPassword absent — sync will refuse
        // to run loudly until the user re-logs.
        assertEquals("uid-no-key", secretStore.uid())
        assertNull(secretStore.keyPassword())
    }

    // --- helpers ---

    private fun newOrchestrator(): SrpLoginOrchestrator = SrpLoginOrchestrator(
        api = api(),
        usersApi = usersApi(),
        srp = SrpClient(random = seededRandom()),
        secretStore = secretStore,
        session = session,
        // Bypass server-proof verification — tests of orchestrator wiring
        // shouldn't double as full SRP end-to-end vectors.
        serverProofVerifier = { _, _ -> true }
    )

    private fun apiFactory() = ProtonApiFactory(
        config = ProtonApiConfig(baseUrl = server.url("/").toString()),
        session = session
    )

    private fun api() = apiFactory().auth

    private fun usersApi() = apiFactory().users

    private fun seededRandom() = SecureRandom.getInstance("SHA1PRNG").apply { setSeed(byteArrayOf(7, 7, 7)) }

    private fun enqueueUserResponse(primaryKeyId: String) {
        server.enqueue(MockResponse().setBody(
            """
            {
                "Code":1000,
                "User":{
                    "ID":"user-1",
                    "Keys":[
                        {"ID":"$primaryKeyId","Version":3,"Primary":1,"Active":1,
                         "PrivateKey":"-----BEGIN PGP PRIVATE KEY BLOCK-----...",
                         "Fingerprint":"deadbeef","Flags":3}
                    ]
                }
            }
            """.trimIndent()
        ))
    }

    private fun enqueueKeySaltsResponse(primaryKeyId: String, saltB64: String) {
        server.enqueue(MockResponse().setBody(
            """
            {
                "Code":1000,
                "KeySalts":[
                    {"ID":"$primaryKeyId","KeySalt":"$saltB64"}
                ]
            }
            """.trimIndent()
        ))
    }

    private fun enqueueInfoResponse() {
        val modulusB64 = Base64.getEncoder().encodeToString(N1024.toByteArray().let {
            // strip the BigInteger sign byte if present so the on-wire bytes
            // are an unsigned representation, matching how Proton ships N.
            if (it.isNotEmpty() && it[0] == 0.toByte() && it.size > 1) it.copyOfRange(1, it.size) else it
        })
        val saltB64 = Base64.getEncoder().encodeToString(saltBytes)
        val bB64 = Base64.getEncoder().encodeToString(serverEphemeralBytes)
        server.enqueue(MockResponse().setBody(
            """
            {
                "Modulus":"$modulusB64",
                "ServerEphemeral":"$bB64",
                "Version":4,
                "Salt":"$saltB64",
                "SRPSession":"session-id",
                "Code":1000
            }
            """.trimIndent()
        ))
    }

    private fun enqueueAuthResponse(uid: String, twoFactor: Int) {
        server.enqueue(MockResponse().setBody(
            """
            {
                "AccessToken":"access-token-XYZ",
                "RefreshToken":"refresh-token-XYZ",
                "TokenType":"Bearer",
                "ExpiresIn":86400,
                "UID":"$uid",
                "UserID":"user-1",
                "PasswordMode":1,
                "TwoFactor":$twoFactor,
                "ServerProof":"${Base64.getEncoder().encodeToString(ByteArray(64) { 0x42 })}",
                "Code":1000
            }
            """.trimIndent()
        ))
    }
}
