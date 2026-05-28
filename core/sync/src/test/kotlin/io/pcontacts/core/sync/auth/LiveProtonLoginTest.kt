// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.sync.auth

import io.pcontacts.core.crypto.openpgp.BouncyCastleKeyUnlock
import io.pcontacts.core.crypto.openpgp.BouncyCastleOpenPgpService
import io.pcontacts.core.crypto.srp.SrpClient
import io.pcontacts.core.proton.api.InMemorySession
import io.pcontacts.core.proton.api.ProtonApiConfig
import io.pcontacts.core.proton.api.retrofit.ProtonApiFactory
import io.pcontacts.core.protoncontacts.ContactDecrypter
import io.pcontacts.core.protoncontacts.ContactProcessor
import io.pcontacts.core.storage.InMemorySecretStore
import io.pcontacts.core.sync.contacts.decrypt.OpenPgpCardCryptoOp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.security.SecureRandom

/**
 * Live integration test against the real Proton API.
 *
 * Skipped by default — runs only when the env var
 * `PCONTACTS_LIVE_TEST` is set to `true`.
 *
 * This test validates end-to-end:
 *   - auth/info DTO shape + modulus OpenPGP signature verification
 *   - SRP hashPassword v4 + proof generation (go-srp variant)
 *   - Little-endian byte encoding for all SRP BigInteger values
 *   - Server accepts the SRP proof (ServerProof round-trip)
 *   - auth response DTO shape (tokens, UID, twoFactor)
 *   - ChallengePayload empty-map acceptance
 *   - /users + /keys/salts DTO shapes
 *   - keyPassword derivation (computeKeyPassword)
 *   - PGP private key unlock with derived keyPassword
 *   - contacts/v4/contacts/emails DTO shape + pagination
 *   - contacts/v4/contacts/{id} full Cards[] fetch
 *   - Card decrypt + signature verification + vCard merge
 */
class LiveProtonLoginTest {

    @Test fun live_login_against_proton_api() = runBlocking {
        assumeTrue("Set env PCONTACTS_LIVE_TEST=true", System.getenv("PCONTACTS_LIVE_TEST") == "true")

        val username = System.getenv("PCONTACTS_USERNAME").orEmpty()
            .also { check(it.isNotBlank()) { "Set env PCONTACTS_USERNAME" } }
        val password = System.getenv("PCONTACTS_PASSWORD").orEmpty()
            .also { check(it.isNotBlank()) { "Set env PCONTACTS_PASSWORD" } }
            .toCharArray()

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

                validateKeyUnlockAndContacts(apiFactory, secretStore, password)

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
            is LoginResult.HumanVerificationRequired -> {
                println("HUMAN_VERIFICATION_REQUIRED — Proton demanded captcha at /auth")
                println("  verificationUrl: ${result.verificationUrl ?: "(none — fail-closed fallback)"}")
                println("  This still validates: modulus sig, SRP math, DTO shapes, 9001 interceptor")
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

    /**
     * Post-login validation: unlock PGP key, fetch contacts, decrypt.
     * Exercises the full chain from keyPassword → unlocked key →
     * contact fetch → card decrypt → vCard merge → DecryptedContact.
     */
    private suspend fun validateKeyUnlockAndContacts(
        apiFactory: ProtonApiFactory,
        secretStore: InMemorySecretStore,
        password: CharArray
    ) {
        // --- Step 1: PGP key unlock ---
        val keyPasswordBytes = secretStore.keyPassword()
        assertNotNull("keyPassword must be stored after login", keyPasswordBytes)
        val keyPassword = String(keyPasswordBytes!!, Charsets.UTF_8).toCharArray()

        val userResponse = apiFactory.users.getUser()
        val primaryKey = userResponse.user.keys.firstOrNull { it.primary == 1 && it.active == 1 }
        assertNotNull("user must have an active primary key", primaryKey)

        println("  --- PGP key unlock ---")
        println("  primary key ID: ${primaryKey!!.id.take(8)}...")

        val unlockedKey = BouncyCastleKeyUnlock.unlock(primaryKey.privateKey, keyPassword)
        println("  key unlock: OK")

        // --- Step 2: List contact emails ---
        println("  --- Contact emails ---")
        val emailsPage = apiFactory.contacts.listContactEmails(page = 0, pageSize = 50)
        println("  total contacts with emails: ${emailsPage.total}")
        println("  emails on page 0: ${emailsPage.contactEmails.size}")

        assertTrue("account should have at least one contact email", emailsPage.contactEmails.isNotEmpty())

        val firstEmail = emailsPage.contactEmails.first()
        println("  first contact: name=${firstEmail.name}, contactId=${firstEmail.contactId.take(8)}...")

        // --- Step 3: Fetch full contact + decrypt ---
        println("  --- Full contact fetch + decrypt ---")
        val contactResponse = apiFactory.contacts.getContact(firstEmail.contactId)
        val contact = contactResponse.contact
        println("  contact ID: ${contact.id.take(8)}...")
        println("  cards count: ${contact.cards.size}")
        println("  card types: ${contact.cards.map { it.type }}")

        assertFalse("contact must have at least one Card", contact.cards.isEmpty())

        val openPgp = BouncyCastleOpenPgpService()
        println("  unlocked keys: ${unlockedKey.allPrivateKeys.size} (primary + subkeys)")
        val cryptoOp = OpenPgpCardCryptoOp.build(
            openPgp = openPgp,
            decryptionKeys = unlockedKey.allPrivateKeys,
            verificationKeys = listOf(unlockedKey.public)
        )
        val processor = ContactProcessor(ContactDecrypter(cryptoOp))
        val decrypted = processor.process(contact)

        println("  decrypted fullName: ${decrypted.fullName}")
        println("  decrypted emails: ${decrypted.emails.size}")
        println("  decrypted phones: ${decrypted.phones.size}")
        println("  decrypted verified: ${decrypted.verified}")
        println("  decrypted cardCount: ${decrypted.cardCount}")
        println("  decrypted unverifiedCardCount: ${decrypted.unverifiedCardCount}")

        assertTrue(
            "contact must have a name or at least one email",
            decrypted.fullName != null || decrypted.emails.isNotEmpty()
        )
        assertTrue(
            "all cards should verify against the user's own key",
            decrypted.verified
        )
        println("  contact decrypt: OK — full chain validated")
    }
}
