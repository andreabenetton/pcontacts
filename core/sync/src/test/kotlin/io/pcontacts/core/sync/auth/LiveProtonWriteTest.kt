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
import io.pcontacts.core.proton.api.contacts.ContactsMetadataPager
import io.pcontacts.core.proton.api.contacts.CreateContactsRequest
import io.pcontacts.core.proton.api.contacts.LabelContactEmailsRequest
import io.pcontacts.core.proton.api.contacts.UpdateContactRequest
import io.pcontacts.core.proton.api.labels.LabelType
import io.pcontacts.core.proton.api.retrofit.ProtonApiFactory
import io.pcontacts.core.protoncontacts.CardEncryptOp
import io.pcontacts.core.protoncontacts.CardEncryptRequest
import io.pcontacts.core.protoncontacts.CardType
import io.pcontacts.core.protoncontacts.ContactDecrypter
import io.pcontacts.core.protoncontacts.ContactPatch
import io.pcontacts.core.protoncontacts.ContactProcessor
import io.pcontacts.core.protoncontacts.ContactSerializer
import io.pcontacts.core.protoncontacts.DecryptedAddress
import io.pcontacts.core.protoncontacts.DecryptedContact
import io.pcontacts.core.protoncontacts.DecryptedEmail
import io.pcontacts.core.protoncontacts.DecryptedIm
import io.pcontacts.core.protoncontacts.DecryptedOrganization
import io.pcontacts.core.protoncontacts.DecryptedPhone
import io.pcontacts.core.protoncontacts.DecryptedPhoto
import io.pcontacts.core.protoncontacts.DecryptedStructuredName
import io.pcontacts.core.storage.InMemorySecretStore
import io.pcontacts.core.sync.contacts.decrypt.OpenPgpCardCryptoOp
import io.pcontacts.core.sync.contacts.encrypt.ContactEncryptBootstrap
import io.pcontacts.core.sync.contacts.encrypt.OpenPgpCardEncryptOp
import io.pcontacts.core.sync.contacts.merge.MergeBaseCodec
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
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
            assertGroupMembershipRoundTrip(apiFactory, createdId)

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

    /**
     * Load-test helper, not part of the canary: `PCONTACTS_SEED=<count>` creates that many
     * encrypted and signed contacts named "pcontacts seed NNNN", `PCONTACTS_SEED=clean`
     * deletes them again (issue #70: a first sync of a large account).
     */
    @Test fun live_seed_or_clean() = runBlocking {
        val seed = System.getenv("PCONTACTS_SEED")
        assumeTrue("Set env PCONTACTS_SEED=<count> or clean", !seed.isNullOrBlank())
        assumeTrue("Set env PCONTACTS_LIVE_TEST=true", System.getenv("PCONTACTS_LIVE_TEST") == "true")
        val username = System.getenv("PCONTACTS_USERNAME").orEmpty()
        val password = System.getenv("PCONTACTS_PASSWORD").orEmpty().toCharArray()
        val crypto = loginAndBuildCrypto(username, password)
        try {
            if (seed == "clean") cleanSeeded(crypto.apiFactory) else seedContacts(crypto, seed.toInt())
        } finally {
            runCatching { crypto.apiFactory.auth.revoke() }
        }
    }

    /**
     * Probe, not part of the canary: `PCONTACTS_PROBE_DUPLICATE_UID=true` posts one contact twice
     * with the same UID and reports what Proton did with the second, then deletes both. Settles the
     * `[U]` behind a create retried after a lost response (ADR-0017, 2026-10-14).
     */
    @Test fun live_duplicate_uid_probe() = runBlocking {
        val probe = System.getenv("PCONTACTS_PROBE_DUPLICATE_UID") == "true"
        assumeTrue("Set env PCONTACTS_PROBE_DUPLICATE_UID=true", probe)
        assumeTrue("Set env PCONTACTS_LIVE_TEST=true", System.getenv("PCONTACTS_LIVE_TEST") == "true")
        val username = System.getenv("PCONTACTS_USERNAME").orEmpty()
        val password = System.getenv("PCONTACTS_PASSWORD").orEmpty().toCharArray()
        val crypto = loginAndBuildCrypto(username, password)
        val marker = UUID.randomUUID().toString().take(8)
        val contact = buildTestContact(marker)
        try {
            val request = CreateContactsRequest(
                contacts = listOf(ContactCardBundle(cards = crypto.serializer.serialize(contact)))
            )
            repeat(2) { attempt ->
                val response = crypto.apiFactory.contacts.createContacts(request)
                val item = response.responses.firstOrNull()?.response
                val outcome = "envelope Code=${response.code} item Code=${item?.code} created=${item?.contact != null}"
                println("  post ${attempt + 1}: $outcome")
            }
            val holders = ContactsMetadataPager(crypto.apiFactory.contacts).metadata().toList()
                .filter { it.uid == contact.protonUid }
            println("  contacts holding the UID: ${holders.size}")
        } finally {
            val ids = ContactsMetadataPager(crypto.apiFactory.contacts).metadata().toList()
                .filter { it.name == contact.fullName }
                .map { it.id }
            if (ids.isNotEmpty()) crypto.apiFactory.contacts.deleteContacts(BulkDeleteRequest(ids = ids))
            println("  cleanup: deleted ${ids.size}")
            runCatching { crypto.apiFactory.auth.revoke() }
        }
    }

    /**
     * Device-test helper, not part of the canary: edits a contact on Proton the way another client
     * would. `PCONTACTS_EDIT_NAME=<display name>` picks the contact; `PCONTACTS_EDIT_PHONE=<number>|<type>`
     * adds that phone; `PCONTACTS_EDIT_EMAIL_TYPE=<address>|<type>` sets an email's type (empty for
     * none). Without either it only prints the contact's numbers, emails and types (synthetic test
     * contacts only).
     */
    @Test fun live_edit_by_name() = runBlocking {
        val name = System.getenv("PCONTACTS_EDIT_NAME")
        assumeTrue("Set env PCONTACTS_EDIT_NAME", !name.isNullOrBlank())
        assumeTrue("Set env PCONTACTS_LIVE_TEST=true", System.getenv("PCONTACTS_LIVE_TEST") == "true")
        val username = System.getenv("PCONTACTS_USERNAME").orEmpty()
        val password = System.getenv("PCONTACTS_PASSWORD").orEmpty().toCharArray()
        val crypto = loginAndBuildCrypto(username, password)
        try {
            val id = ContactsMetadataPager(crypto.apiFactory.contacts).metadata().toList().single { it.name == name }.id
            val server = fetchAndDecrypt(crypto.apiFactory, crypto.processor, id)
            System.getenv("PCONTACTS_EDIT_PHONE")?.split("|")?.let { (number, type) ->
                val canonical = MergeBaseCodec.canonical(server)!!
                val edited = canonical.copy(phones = canonical.phones + DecryptedPhone(number, listOf(type)))
                val patch = ContactPatch.diff(canonical, edited)
                val cards = crypto.serializer.serialize(server.cards, patch, "unused")
                val response = crypto.apiFactory.contacts.updateContact(id, UpdateContactRequest(cards))
                println("  edit: Code=${response.code}")
            }
            System.getenv("PCONTACTS_EDIT_EMAIL_TYPE")?.split("|")?.let { (address, type) ->
                val canonical = MergeBaseCodec.canonical(server)!!
                val types = listOf(type).filter { it.isNotBlank() }
                val edited = canonical.copy(
                    emails = canonical.emails.map { if (it.address == address) it.copy(types = types) else it }
                )
                val cards = crypto.serializer.serialize(server.cards, ContactPatch.diff(canonical, edited), "unused")
                val response = crypto.apiFactory.contacts.updateContact(id, UpdateContactRequest(cards))
                println("  email type: Code=${response.code}")
            }
            val after = fetchAndDecrypt(crypto.apiFactory, crypto.processor, id)
            println("  phones on Proton: ${after.phones.map { "${it.number}|${it.types}" }}")
            println("  emails on Proton: ${after.emails.map { "${it.address}|${it.types}" }}")
            val photoLine = after.cards.flatMap { it.plaintext.lines() }.firstOrNull { it.startsWith("PHOTO") }
            val photoOut = System.getenv("PCONTACTS_EDIT_PHOTO_OUT")
            val bytes = after.photo?.data
            if (photoOut != null && bytes != null) java.io.File(photoOut).writeBytes(bytes)
            println("  photo on Proton: ${after.photo?.data?.size ?: 0} bytes, ${photoLine?.substringBefore(",")}")
        } finally {
            runCatching { crypto.apiFactory.auth.revoke() }
        }
    }

    /**
     * Probe, not part of the canary: `PCONTACTS_PROBE_FIELDS=true` creates one throwaway contact
     * per field value the phone side can produce and prints Proton's item code for each, then
     * deletes them. Finds which value Proton refuses with an undocumented code (`[U]` 2060).
     */
    @Test fun live_field_probe() = runBlocking {
        assumeTrue("Set env PCONTACTS_PROBE_FIELDS=true", System.getenv("PCONTACTS_PROBE_FIELDS") == "true")
        assumeTrue("Set env PCONTACTS_LIVE_TEST=true", System.getenv("PCONTACTS_LIVE_TEST") == "true")
        val username = System.getenv("PCONTACTS_USERNAME").orEmpty()
        val password = System.getenv("PCONTACTS_PASSWORD").orEmpty().toCharArray()
        val crypto = loginAndBuildCrypto(username, password)
        val base = buildTestContact("probe").copy(phones = emptyList(), notes = emptyList())
        val probes = linkedMapOf(
            "baseline" to base,
            "birthday full" to base.copy(birthday = "1980-03-15"),
            "birthday no year" to base.copy(birthday = "--03-15"),
            "birthday text" to base.copy(birthday = "March 15"),
            "anniversary no year" to base.copy(anniversary = "--06-01"),
            "website no scheme" to base.copy(websites = listOf("example.com")),
            "website scheme" to base.copy(websites = listOf("https://example.com")),
            "phone letters" to base.copy(phones = listOf(DecryptedPhone("+1 555 CALL ME", listOf("cell")))),
            "phone extension" to base.copy(phones = listOf(DecryptedPhone("+1 555 0100 ext. 12", listOf("work")))),
            "email invalid" to base.copy(emails = listOf(DecryptedEmail("not an email", isPrimary = true))),
            "email spaces" to base.copy(emails = listOf(DecryptedEmail(" spaced@example.com ", isPrimary = true))),
            "email none" to base.copy(emails = emptyList()),
            "nickname" to base.copy(nicknames = listOf("Probe")),
            "address country only" to base.copy(addresses = listOf(DecryptedAddress(country = "Italy"))),
            "im" to base.copy(imAccounts = listOf(DecryptedIm("probe.handle", "skype"))),
            "organization title only" to base.copy(organization = DecryptedOrganization(title = "Engineer")),
            "note long" to base.copy(notes = listOf("x".repeat(LONG_NOTE))),
            // WebClients contacts/constants.ts: "the backend has a limit of 190 chars for a contact
            // name", 94 for a first or last name; Proton's editor never sends a longer one.
            "name 190" to base.copy(fullName = probeName(NAME_LIMIT)),
            "name 191" to base.copy(fullName = probeName(NAME_LIMIT + 1)),
            "name 400" to base.copy(fullName = probeName(LONG_NAME)),
            "given 94" to base.copy(structuredName = DecryptedStructuredName(given = probeName(PART_LIMIT))),
            "given 95" to base.copy(structuredName = DecryptedStructuredName(given = probeName(PART_LIMIT + 1))),
            "family 400" to base.copy(structuredName = DecryptedStructuredName(family = probeName(LONG_NAME)))
        )
        // JPEG files named by the env var, sent the way the push sends a phone photo.
        System.getenv("PCONTACTS_PROBE_PHOTOS")?.split(",")?.forEach { path ->
            val bytes = java.io.File(path).readBytes()
            probes["photo ${bytes.size / KIB} KiB"] = base.copy(photo = DecryptedPhoto(bytes))
        }
        val created = mutableListOf<String>()
        try {
            for ((tag, contact) in probes) {
                val name = contact.fullName.takeIf { it != base.fullName } ?: "pcontacts probe $tag"
                val probe = contact.copy(fullName = name, protonUid = "urn:uuid:${UUID.randomUUID()}")
                val request = CreateContactsRequest(listOf(ContactCardBundle(crypto.serializer.serialize(probe))))
                val item = crypto.apiFactory.contacts.createContacts(request).responses.firstOrNull()?.response
                item?.contact?.id?.let { created += it }
                println("  probe [$tag]: item Code=${item?.code}")
                delay(SEED_PAUSE_MS)
            }
            // The same values reached by an update of a plain contact, through the push's patch path.
            for ((tag, contact) in probes) {
                val plain = base.copy(
                    fullName = "pcontacts probe update $tag",
                    protonUid = "urn:uuid:${UUID.randomUUID()}"
                )
                val request = CreateContactsRequest(listOf(ContactCardBundle(crypto.serializer.serialize(plain))))
                val response = crypto.apiFactory.contacts.createContacts(request)
                val id = response.responses.firstOrNull()?.response?.contact?.id ?: continue
                created += id
                val server = fetchAndDecrypt(crypto.apiFactory, crypto.processor, id)
                val canonical = MergeBaseCodec.canonical(server)!!
                val name = contact.fullName.takeIf { it != base.fullName } ?: canonical.fullName
                val target = contact.copy(fullName = name, protonUid = canonical.protonUid)
                val patch = ContactPatch.diff(canonical, target)
                val cards = crypto.serializer.serialize(server.cards, patch, "unused")
                val update = UpdateContactRequest(cards)
                val outcome = runCatching { crypto.apiFactory.contacts.updateContact(id, update).code }
                println("  probe update [$tag]: ${outcome.getOrElse(::refusal)}")
                delay(SEED_PAUSE_MS)
            }
        } finally {
            if (created.isNotEmpty()) crypto.apiFactory.contacts.deleteContacts(BulkDeleteRequest(ids = created))
            println("  cleanup: deleted ${created.size}")
            runCatching { crypto.apiFactory.auth.revoke() }
        }
    }

    /**
     * Probe, not part of the canary: `PCONTACTS_PROBE_STRUCTURE=true` updates one throwaway contact
     * with hand-built cards that break one rule of the card layout Proton's client always keeps
     * (`[V]` WebClients `contacts/encrypt.ts`), and prints what Proton answers to each. Value checks
     * answer HTTP 422; a structural one answered HTTP 400 (2002, missing UID), as #71's 2060 did.
     */
    @Suppress("LongMethod")
    @Test
    fun live_structure_probe() = runBlocking {
        assumeTrue("Set env PCONTACTS_PROBE_STRUCTURE=true", System.getenv("PCONTACTS_PROBE_STRUCTURE") == "true")
        assumeTrue("Set env PCONTACTS_LIVE_TEST=true", System.getenv("PCONTACTS_LIVE_TEST") == "true")
        val username = System.getenv("PCONTACTS_USERNAME").orEmpty()
        val password = System.getenv("PCONTACTS_PASSWORD").orEmpty().toCharArray()
        val crypto = loginAndBuildCrypto(username, password)
        val uid = "urn:uuid:${UUID.randomUUID()}"
        val base = buildTestContact("structure").copy(protonUid = uid, fullName = "pcontacts probe structure")
        val created = crypto.apiFactory.contacts.createContacts(
            CreateContactsRequest(listOf(ContactCardBundle(crypto.serializer.serialize(base))))
        ).responses.firstOrNull()?.response?.contact?.id
        assertNotNull("probe contact created", created)
        val id = created!!

        fun vcard(vararg lines: String) = (listOf("BEGIN:VCARD", "VERSION:4.0") + lines + "END:VCARD")
            .joinToString("\r\n")
        fun signed(text: String) = ContactCardDto(
            type = CardType.SIGNED.wireValue,
            data = text,
            signature = crypto.encryptOp(CardEncryptRequest.SignOnly(text)).signature
        )
        fun encrypted(text: String) = crypto.encryptOp(CardEncryptRequest.EncryptAndSign(text)).let {
            ContactCardDto(type = CardType.ENCRYPTED_AND_SIGNED.wireValue, data = it.data, signature = it.signature)
        }
        val fn = "FN:pcontacts probe structure"
        val uidLine = "UID:$uid"
        val email = "ITEM1.EMAIL;PREF=1:structure@example.com"
        val tel = "TEL;TYPE=cell:+1 555 0100"
        val goodSigned = vcard(fn, uidLine, email)
        val probes = linkedMapOf(
            "baseline" to listOf(signed(goodSigned), encrypted(vcard(tel))),
            "signed bad signature" to listOf(
                signed(goodSigned).copy(signature = signed(vcard(fn, uidLine)).signature),
                encrypted(vcard(tel))
            ),
            "encrypted bad signature" to listOf(
                signed(goodSigned),
                encrypted(vcard(tel)).copy(signature = encrypted(vcard("NOTE:other")).signature)
            ),
            "signed no FN" to listOf(signed(vcard(uidLine, email)), encrypted(vcard(tel))),
            "signed two FN" to listOf(signed(vcard(fn, "FN:other", uidLine, email)), encrypted(vcard(tel))),
            "signed no UID" to listOf(signed(vcard(fn, email)), encrypted(vcard(tel))),
            "signed no VERSION" to listOf(
                signed(listOf("BEGIN:VCARD", fn, uidLine, email, "END:VCARD").joinToString("\r\n")),
                encrypted(vcard(tel))
            ),
            "signed VERSION 3.0" to listOf(signed(goodSigned.replace("VERSION:4.0", "VERSION:3.0")), encrypted(vcard(tel))),
            "signed email no group" to listOf(
                signed(vcard(fn, uidLine, "EMAIL;PREF=1:structure@example.com")),
                encrypted(vcard(tel))
            ),
            "TEL in signed card" to listOf(signed(vcard(fn, uidLine, email, tel))),
            "FN also in encrypted card" to listOf(signed(goodSigned), encrypted(vcard(fn, tel))),
            "UID also in encrypted card" to listOf(signed(goodSigned), encrypted(vcard(uidLine, tel))),
            "EMAIL also in encrypted card" to listOf(signed(goodSigned), encrypted(vcard(email, tel))),
            "two signed cards" to listOf(signed(goodSigned), signed(goodSigned), encrypted(vcard(tel))),
            "two encrypted cards" to listOf(signed(goodSigned), encrypted(vcard(tel)), encrypted(vcard("NOTE:x"))),
            "no signed card" to listOf(encrypted(vcard(fn, uidLine, email, tel))),
            "clear card with TEL" to listOf(
                signed(goodSigned),
                ContactCardDto(type = CardType.CLEAR_TEXT.wireValue, data = vcard(tel))
            ),
            "CATEGORIES in encrypted card" to listOf(signed(goodSigned), encrypted(vcard(tel, "CATEGORIES:probe"))),
            "empty encrypted card" to listOf(signed(goodSigned), encrypted(vcard())),
            "empty TEL value" to listOf(signed(goodSigned), encrypted(vcard("TEL;TYPE=cell:"))),
            "empty ADR value" to listOf(signed(goodSigned), encrypted(vcard("ADR;TYPE=home:;;;;;;"))),
            "unknown card type" to listOf(signed(goodSigned), encrypted(vcard(tel)).copy(type = 4)),
            // Last: a success would change the contact's UID for the probes after it.
            "UID changed" to listOf(signed(vcard(fn, "UID:urn:uuid:${UUID.randomUUID()}", email)), encrypted(vcard(tel)))
        )
        try {
            for ((tag, cards) in probes) {
                val update = UpdateContactRequest(cards)
                val outcome = runCatching { crypto.apiFactory.contacts.updateContact(id, update).code }
                println("  structure [$tag]: ${outcome.getOrElse(::refusal)}")
                delay(SEED_PAUSE_MS)
            }
        } finally {
            crypto.apiFactory.contacts.deleteContacts(BulkDeleteRequest(ids = listOf(id)))
            println("  cleanup: deleted $id")
            runCatching { crypto.apiFactory.auth.revoke() }
        }
    }

    private fun probeName(length: Int) = "pcontacts probe ".padEnd(length, 'x')

    private fun refusal(t: Throwable): String = (t as? retrofit2.HttpException)
        ?.let { e -> "HTTP ${e.code()} ${e.response()?.errorBody()?.string()?.take(PROBE_BODY)}" }
        ?: t.javaClass.simpleName

    private suspend fun seedContacts(crypto: CryptoContext, count: Int) {
        var created = 0
        for (batch in (1..count).chunked(SEED_BATCH)) {
            val bundles = batch.map { ContactCardBundle(cards = crypto.serializer.serialize(seedContact(it))) }
            val response = crypto.apiFactory.contacts.createContacts(CreateContactsRequest(contacts = bundles))
            created += response.responses.count { it.response.code == SUCCESS }
            delay(SEED_PAUSE_MS) // Proton allows about 100 calls per 10 s
        }
        println("  seed: created $created of $count")
    }

    private fun seedContact(i: Int): DecryptedContact {
        val n = "%04d".format(i)
        return DecryptedContact(
            protonContactId = "",
            protonUid = "urn:uuid:${UUID.randomUUID()}",
            fullName = "pcontacts seed $n",
            structuredName = DecryptedStructuredName(given = "Seed", family = "pcontacts $n"),
            emails = listOf(DecryptedEmail(address = "seed-$n@example.invalid", types = listOf("home"))),
            phones = listOf(DecryptedPhone(number = "+1-555-01${n.takeLast(2)}", types = listOf("cell"))),
            notes = listOf("pcontacts load-test contact — safe to delete"),
            verified = true,
            cardCount = 2,
            unverifiedCardCount = 0
        )
    }

    private suspend fun cleanSeeded(apiFactory: ProtonApiFactory) {
        val ids = ContactsMetadataPager(apiFactory.contacts).metadata().toList()
            .filter { it.name.startsWith("pcontacts seed ") }
            .map { it.id }
        for (batch in ids.chunked(CLEAN_BATCH)) {
            apiFactory.contacts.deleteContacts(BulkDeleteRequest(ids = batch))
            delay(SEED_PAUSE_MS)
        }
        println("  clean: deleted ${ids.size} seeded contacts")
    }

    private companion object {
        const val SEED_BATCH = 10
        const val CLEAN_BATCH = 50
        const val SEED_PAUSE_MS = 200L
        const val SUCCESS = 1000
        const val LONG_NOTE = 20_000
        const val NAME_LIMIT = 190
        const val PART_LIMIT = 94
        const val LONG_NAME = 400
        const val KIB = 1024
        const val PROBE_BODY = 160
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
        println("  login result: $result")
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

    /**
     * ADR-0027: membership lives on ContactEmails; label then unlabel the canary's email with
     * an existing contact group and read it back. `PCONTACTS_TEST_GROUP` names the group to use
     * (on a real account, a probe group: no real group is touched); without it, the first one.
     * Skipped when there is no such group.
     */
    private suspend fun assertGroupMembershipRoundTrip(apiFactory: ProtonApiFactory, id: String) {
        val wanted = System.getenv("PCONTACTS_TEST_GROUP")
        val groups = apiFactory.labels.listLabels(LabelType.CONTACT_GROUP).labels
        val group = if (wanted.isNullOrBlank()) groups.firstOrNull() else groups.firstOrNull { it.name == wanted }
        if (group == null) {
            println("  groups: no usable group on this account, label round trip skipped")
            return
        }
        val before = apiFactory.contacts.getContact(id).contact
        val emailIds = before.contactEmails.map { it.id }
        assertTrue("the canary must have a ContactEmail", emailIds.isNotEmpty())

        val labelled = apiFactory.contacts.labelContactEmails(LabelContactEmailsRequest(group.id, emailIds))
        println(
            "  label: Code ${labelled.code}, ${labelled.responses.size} item responses " +
                "${labelled.responses.map { it.response.code }}"
        )
        val afterLabel = apiFactory.contacts.getContact(id).contact
        println("  label: ModifyTime moved ${afterLabel.modifyTime != before.modifyTime}")
        assertTrue(afterLabel.contactEmails.all { group.id in it.labelIds })
        assertTrue("the contact's LabelIDs follow its emails", group.id in afterLabel.labelIds)

        apiFactory.contacts.unlabelContactEmails(LabelContactEmailsRequest(group.id, emailIds))
        val afterUnlabel = apiFactory.contacts.getContact(id).contact
        assertTrue(afterUnlabel.contactEmails.none { group.id in it.labelIds })
        assertTrue(group.id !in afterUnlabel.labelIds)
        println("  unlabel: OK")
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
