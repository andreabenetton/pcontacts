// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.sync.auth

import io.pcontacts.core.crypto.openpgp.BouncyCastleKeyUnlock
import io.pcontacts.core.crypto.openpgp.BouncyCastleOpenPgpService
import io.pcontacts.core.crypto.srp.SrpClient
import io.pcontacts.core.proton.api.InMemorySession
import io.pcontacts.core.proton.api.ProtonApiConfig
import io.pcontacts.core.proton.api.contacts.BulkDeleteRequest
import io.pcontacts.core.proton.api.contacts.ContactCardBundle
import io.pcontacts.core.proton.api.contacts.CreateContactsRequest
import io.pcontacts.core.proton.api.retrofit.ProtonApiFactory
import io.pcontacts.core.protoncontacts.ContactDecrypter
import io.pcontacts.core.protoncontacts.ContactProcessor
import io.pcontacts.core.protoncontacts.ContactSerializer
import io.pcontacts.core.protoncontacts.DecryptedContact
import io.pcontacts.core.protoncontacts.DecryptedEmail
import io.pcontacts.core.protoncontacts.DecryptedPhone
import io.pcontacts.core.protoncontacts.DecryptedStructuredName
import io.pcontacts.core.storage.InMemorySecretStore
import io.pcontacts.core.sync.contacts.decrypt.OpenPgpCardCryptoOp
import io.pcontacts.core.sync.contacts.encrypt.ContactEncryptBootstrap
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.security.SecureRandom
import java.util.UUID

/**
 * Live write-path canary against the real Proton API.
 *
 * Skipped by default — runs only when `PCONTACTS_LIVE_TEST=true`.
 *
 * Round-trip: create a throwaway contact → fetch it back → assert
 * field equality → delete it. The contact uses a unique marker in
 * the note field so stale test contacts can be identified if
 * deletion fails.
 *
 * This test exercises:
 *   - ContactSerializer (vCard → SIGNED + ENCRYPTED_AND_SIGNED cards)
 *   - OpenPgpCardEncryptOp (real BouncyCastle encrypt + sign)
 *   - POST contacts/v4/contacts (create) DTO shape
 *   - GET contacts/v4/contacts/{id} (fetch-back) DTO shape
 *   - Card decrypt + merge (round-trip fidelity)
 *   - PUT contacts/v4/contacts/delete (bulk delete) DTO shape
 */
class LiveProtonWriteTest {

    @Test fun live_write_round_trip() = runBlocking {
        assumeTrue("Set env PCONTACTS_LIVE_TEST=true", System.getenv("PCONTACTS_LIVE_TEST") == "true")

        val username = System.getenv("PCONTACTS_USERNAME").orEmpty()
            .also { check(it.isNotBlank()) { "Set env PCONTACTS_USERNAME" } }
        val password = System.getenv("PCONTACTS_PASSWORD").orEmpty()
            .also { check(it.isNotBlank()) { "Set env PCONTACTS_PASSWORD" } }
            .toCharArray()

        println("=== LiveProtonWriteTest ===")
        val (apiFactory, serializer, processor) = loginAndBuildCrypto(username, password)

        val marker = UUID.randomUUID().toString().take(8)
        val testContact = buildTestContact(marker)
        var createdId: String? = null

        try {
            createdId = createOnServer(apiFactory, serializer, testContact, marker)
            val decrypted = fetchAndDecrypt(apiFactory, processor, createdId)
            assertRoundTrip(testContact, decrypted, marker)

            apiFactory.contacts.deleteContacts(BulkDeleteRequest(ids = listOf(createdId)))
            println("  delete: OK")
            createdId = null
        } finally {
            if (createdId != null) {
                println("  cleanup: deleting leftover contact $createdId")
                runCatching {
                    apiFactory.contacts.deleteContacts(BulkDeleteRequest(ids = listOf(createdId)))
                }
            }
            runCatching { apiFactory.auth.revoke() }
            println("  logout: OK")
        }

        println("=== LiveProtonWriteTest PASS ===")
    }

    private data class CryptoContext(
        val apiFactory: ProtonApiFactory,
        val serializer: ContactSerializer,
        val processor: ContactProcessor
    )

    private suspend fun loginAndBuildCrypto(username: String, password: CharArray): CryptoContext {
        val secretStore = InMemorySecretStore()
        val session = InMemorySession()
        val apiFactory = ProtonApiFactory(config = ProtonApiConfig(), session = session)
        val orchestrator = SrpLoginOrchestrator(
            api = apiFactory.auth,
            usersApi = apiFactory.users,
            srp = SrpClient(random = SecureRandom()),
            secretStore = secretStore,
            session = session
        )
        val result = orchestrator.login(username, password)
        assertTrue("login must succeed", result is LoginResult.Success)

        val keyPasswordBytes = secretStore.keyPassword()!!
        val keyPassword = String(keyPasswordBytes, Charsets.UTF_8).toCharArray()
        val primaryKey = apiFactory.users.getUser().user.keys.first { it.primary == 1 && it.active == 1 }
        val unlockedKey = BouncyCastleKeyUnlock.unlock(primaryKey.privateKey, keyPassword)
        val openPgp = BouncyCastleOpenPgpService()
        val cryptoOp = OpenPgpCardCryptoOp.build(
            openPgp = openPgp,
            decryptionKeys = unlockedKey.allPrivateKeys,
            verificationKeys = listOf(unlockedKey.public)
        )
        return CryptoContext(
            apiFactory = apiFactory,
            serializer = ContactEncryptBootstrap.createSerializer(openPgp, unlockedKey),
            processor = ContactProcessor(ContactDecrypter(cryptoOp))
        )
    }

    private fun buildTestContact(marker: String) = DecryptedContact(
        protonContactId = "",
        protonUid = "urn:uuid:${UUID.randomUUID()}",
        fullName = "pcontacts Canary $marker",
        structuredName = DecryptedStructuredName(given = "Canary", family = "pcontacts $marker"),
        emails = listOf(DecryptedEmail(address = "canary-$marker@test.invalid", types = listOf("home"))),
        phones = listOf(DecryptedPhone(number = "+1-555-0100", types = listOf("cell"))),
        notes = listOf("pcontacts-canary-$marker — safe to delete"),
        verified = true,
        cardCount = 2,
        unverifiedCardCount = 0
    )

    private suspend fun createOnServer(
        apiFactory: ProtonApiFactory,
        serializer: ContactSerializer,
        testContact: DecryptedContact,
        marker: String
    ): String {
        val cards = serializer.serialize(testContact)
        assertEquals("serializer must produce 2 cards (SIGNED + E&S)", 2, cards.size)
        println("  serialize: OK (${cards.size} cards)")
        val createResponse = apiFactory.contacts.createContacts(
            CreateContactsRequest(contacts = listOf(ContactCardBundle(cards = cards)))
        )
        val serverContact = createResponse.responses.firstOrNull()?.response?.contact
        assertNotNull("server must return the created contact", serverContact)
        val createdId = serverContact!!.id
        println("  create: OK (id=${createdId.take(8)}...) marker=$marker")
        return createdId
    }

    private suspend fun fetchAndDecrypt(
        apiFactory: ProtonApiFactory,
        processor: ContactProcessor,
        createdId: String
    ): DecryptedContact {
        val fetchResponse = apiFactory.contacts.getContact(createdId)
        assertEquals("fetched contact ID must match", createdId, fetchResponse.contact.id)
        val decrypted = processor.process(fetchResponse.contact)
        println("  fetch+decrypt: OK fullName=${decrypted.fullName} emails=${decrypted.emails.size}")
        return decrypted
    }

    private fun assertRoundTrip(expected: DecryptedContact, actual: DecryptedContact, marker: String) {
        assertEquals("fullName", expected.fullName, actual.fullName)
        assertEquals("email count", expected.emails.size, actual.emails.size)
        assertEquals("email address", expected.emails[0].address, actual.emails[0].address)
        assertEquals("phone count", expected.phones.size, actual.phones.size)
        assertEquals("phone number", expected.phones[0].number, actual.phones[0].number)
        assertTrue("notes must contain marker", actual.notes.any { it.contains(marker) })
        assertTrue("all cards must verify", actual.verified)
        println("  round-trip assertions: PASS")
    }
}
