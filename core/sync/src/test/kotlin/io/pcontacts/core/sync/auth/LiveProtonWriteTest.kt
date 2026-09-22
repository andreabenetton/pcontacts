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
import io.pcontacts.core.proton.api.contacts.ContactCardDto
import io.pcontacts.core.proton.api.contacts.CreateContactsRequest
import io.pcontacts.core.proton.api.contacts.UpdateContactRequest
import io.pcontacts.core.proton.api.retrofit.ProtonApiFactory
import io.pcontacts.core.protoncontacts.CardEncryptOp
import io.pcontacts.core.protoncontacts.CardEncryptRequest
import io.pcontacts.core.protoncontacts.CardType
import io.pcontacts.core.protoncontacts.ContactDecrypter
import io.pcontacts.core.protoncontacts.ContactPatch
import io.pcontacts.core.protoncontacts.ContactProcessor
import io.pcontacts.core.protoncontacts.ContactSerializer
import io.pcontacts.core.protoncontacts.DecryptedContact
import io.pcontacts.core.protoncontacts.DecryptedEmail
import io.pcontacts.core.protoncontacts.DecryptedPhone
import io.pcontacts.core.protoncontacts.DecryptedStructuredName
import io.pcontacts.core.storage.InMemorySecretStore
import io.pcontacts.core.sync.contacts.decrypt.OpenPgpCardCryptoOp
import io.pcontacts.core.sync.contacts.encrypt.ContactEncryptBootstrap
import io.pcontacts.core.sync.contacts.encrypt.OpenPgpCardEncryptOp
import io.pcontacts.core.sync.contacts.merge.MergeBaseCodec
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
 *   - GET contacts/v4/contacts/emails lists the created address — `[A]`
 *     the server derives ContactEmails from the signed card
 *   - PUT contacts/v4/contacts/{id} with cards patched from the fetched
 *     ones (ADR-0017 Choice 2C): the server accepts them and keeps the
 *     properties the app does not model
 *   - PUT contacts/v4/contacts/delete (bulk delete) DTO shape
 *
 * A two-factor account needs `PCONTACTS_TOTP_CODE` (a fresh TOTP).
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
        val crypto = loginAndBuildCrypto(username, password)
        val apiFactory = crypto.apiFactory
        val serializer = crypto.serializer
        val processor = crypto.processor
        val encryptOp = crypto.encryptOp

        val marker = UUID.randomUUID().toString().take(8)
        val testContact = buildTestContact(marker)
        val created = mutableListOf<String>()

        try {
            val createdId = createOnServer(apiFactory, serializer, testContact, marker)
            created += createdId
            val decrypted = fetchAndDecrypt(apiFactory, processor, createdId)
            assertRoundTrip(testContact, decrypted, marker)
            assertListedAmongContactEmails(apiFactory, "canary-$marker@example.com", createdId)

            val richId = createRichContact(apiFactory, encryptOp, marker)
            created += richId
            assertPatchedUpdateKeepsUnmodelledProperties(apiFactory, serializer, processor, richId, marker)

            apiFactory.contacts.deleteContacts(BulkDeleteRequest(ids = created.toList()))
            println("  delete: OK")
            created.clear()
        } finally {
            if (created.isNotEmpty()) {
                println("  cleanup: deleting leftover contacts ${created.size}")
                runCatching {
                    apiFactory.contacts.deleteContacts(BulkDeleteRequest(ids = created.toList()))
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
        val processor: ContactProcessor,
        val encryptOp: CardEncryptOp
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
        val first = orchestrator.login(username, password)
        val totp = System.getenv("PCONTACTS_TOTP_CODE")
        val result = if (first is LoginResult.TwoFactorRequired && !totp.isNullOrBlank()) {
            orchestrator.submitTwoFactorCode(totp)
        } else {
            first
        }
        println("  login result: ${result.javaClass.simpleName}")
        // Mirror LiveProtonLoginTest's policy: the canary tests Proton-API
        // shape (DTOs, endpoints, x-pm-appversion window). A non-Success
        // outcome — HumanVerificationRequired, TwoFactorRequired, etc. —
        // is normal anti-abuse / account state and not what this test
        // exists to catch. Skip the round-trip; the LoginTest still
        // validated SRP + DTOs end-to-end on the same canary run.
        assumeTrue(
            "non-Success login (likely Proton HV gate at the CI IP) — " +
                "write round-trip cannot run; not a Proton-API regression",
            result is LoginResult.Success
        )

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
            processor = ContactProcessor(ContactDecrypter(cryptoOp)),
            encryptOp = OpenPgpCardEncryptOp.build(
                openPgp = openPgp,
                encryptionKeys = unlockedKey.encryptionPublicKeys.ifEmpty { listOf(unlockedKey.public) },
                signingKey = unlockedKey.private
            )
        )
    }

    /** `[A]` Proton indexes ContactEmails from the signed card, where the create now puts EMAIL. */
    private suspend fun assertListedAmongContactEmails(
        apiFactory: ProtonApiFactory,
        email: String,
        id: String
    ) {
        val page = apiFactory.contacts.listContactEmails(page = 0, pageSize = 100, emailFilter = email)
        val hit = page.contactEmails.any { it.contactId == id && it.email.equals(email, ignoreCase = true) }
        println("  contacts/emails lists the created address: $hit (${page.contactEmails.size} rows)")
        assertTrue("ContactEmails must list the address from the signed card", hit)
    }

    /** A contact shaped like Proton's own: grouped EMAIL in the signed card, unmodelled properties in the other. */
    private suspend fun createRichContact(
        apiFactory: ProtonApiFactory,
        encryptOp: CardEncryptOp,
        marker: String
    ): String {
        val signedText = "BEGIN:VCARD\nVERSION:4.0\nFN:pcontacts Rich $marker\nUID:urn:uuid:${UUID.randomUUID()}\n" +
            "item1.EMAIL;PREF=1:rich-$marker@example.com\nEND:VCARD"
        val encryptedText = "BEGIN:VCARD\nVERSION:4.0\nN:Rich;pcontacts;;;\nTEL;TYPE=cell;PREF=1:+1-555-0101\n" +
            "BDAY:19800101\nURL:https://example.invalid/$marker\nNICKNAME:Rich\n" +
            "NOTE:pcontacts-canary-$marker — safe to delete\nEND:VCARD"
        val signed = encryptOp(CardEncryptRequest.SignOnly(signedText))
        val encrypted = encryptOp(CardEncryptRequest.EncryptAndSign(encryptedText))
        val response = apiFactory.contacts.createContacts(
            CreateContactsRequest(
                contacts = listOf(
                    ContactCardBundle(
                        cards = listOf(
                            ContactCardDto(
                                type = CardType.SIGNED.wireValue,
                                data = signed.data,
                                signature = signed.signature
                            ),
                            ContactCardDto(
                                type = CardType.ENCRYPTED_AND_SIGNED.wireValue,
                                data = encrypted.data,
                                signature = encrypted.signature
                            )
                        )
                    )
                )
            )
        )
        val id = response.responses.firstOrNull()?.response?.contact?.id
        assertNotNull("rich create must return the contact", id)
        println("  rich create: OK (id=${id!!.take(8)}...)")
        return id
    }

    /** ADR-0017 Choice 2C against the real server: a phone edit patched onto the fetched cards. */
    private suspend fun assertPatchedUpdateKeepsUnmodelledProperties(
        apiFactory: ProtonApiFactory,
        serializer: ContactSerializer,
        processor: ContactProcessor,
        id: String,
        marker: String
    ) {
        val server = fetchAndDecrypt(apiFactory, processor, id)
        val serverCanonical = MergeBaseCodec.canonical(server)!!
        val edited = serverCanonical.copy(
            phones = listOf(DecryptedPhone("+1-555-0199", listOf("cell"), isPrimary = true))
        )
        val patch = ContactPatch.diff(serverCanonical, edited)
        val cards = serializer.serialize(server.cards, patch, fallbackUid = "unused")
        val update = apiFactory.contacts.updateContact(id, UpdateContactRequest(cards = cards))
        println("  patched update: Code=${update.code}")

        val after = fetchAndDecrypt(apiFactory, processor, id)
        assertTrue("cards must verify after the patched update", after.verified)
        assertEquals("phone changed", listOf("+1-555-0199"), after.phones.map { it.number })
        assertEquals("uid kept", server.protonUid, after.protonUid)
        val signedBack = after.cards.first { it.originalType == CardType.SIGNED }.plaintext
        val encryptedBack = after.cards.first { it.originalType == CardType.ENCRYPTED_AND_SIGNED }.plaintext
        assertTrue("grouped EMAIL kept", signedBack.contains("item1.EMAIL;PREF=1:rich-$marker@example.com"))
        assertTrue("BDAY kept", encryptedBack.contains("BDAY:19800101"))
        assertTrue("URL kept", encryptedBack.contains("URL:https://example.invalid/$marker"))
        assertTrue("NICKNAME kept", encryptedBack.contains("NICKNAME:Rich"))
        assertTrue("old phone gone", !encryptedBack.contains("+1-555-0101"))
        assertListedAmongContactEmails(apiFactory, "rich-$marker@example.com", id)
        println("  patched update keeps unmodelled properties: PASS")
    }

    private fun buildTestContact(marker: String) = DecryptedContact(
        protonContactId = "",
        protonUid = "urn:uuid:${UUID.randomUUID()}",
        fullName = "pcontacts Canary $marker",
        structuredName = DecryptedStructuredName(given = "Canary", family = "pcontacts $marker"),
        emails = listOf(DecryptedEmail(address = "canary-$marker@example.com", types = listOf("home"))),
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
        val item = createResponse.responses.firstOrNull()?.response
        println("  create envelope Code=${createResponse.code} item Code=${item?.code}")
        val serverContact = item?.contact
        assertNotNull("server must return the created contact (item Code=${item?.code})", serverContact)
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
