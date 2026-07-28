// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.sync.contacts

import android.accounts.Account
import io.pcontacts.core.contactswriter.ApplyResult
import io.pcontacts.core.contactswriter.RawContactOpIntent
import io.pcontacts.core.proton.api.contacts.ContactCardDto
import io.pcontacts.core.proton.api.contacts.ContactDto
import io.pcontacts.core.proton.api.contacts.ContactEmailsPageResponse
import io.pcontacts.core.proton.api.contacts.ContactMetadataDto
import io.pcontacts.core.proton.api.contacts.ContactsMetadataPager
import io.pcontacts.core.proton.api.contacts.ContactsPageResponse
import io.pcontacts.core.proton.api.contacts.BulkDeleteRequest
import io.pcontacts.core.proton.api.contacts.BulkDeleteResponse
import io.pcontacts.core.proton.api.contacts.CreateContactsRequest
import io.pcontacts.core.proton.api.contacts.CreateContactsResponse
import io.pcontacts.core.proton.api.contacts.GetContactResponse
import io.pcontacts.core.proton.api.contacts.ProtonContactsApi
import io.pcontacts.core.proton.api.contacts.UpdateContactRequest
import io.pcontacts.core.proton.api.contacts.UpdateContactResponse
import io.pcontacts.core.proton.api.labels.GetLabelsResponse
import io.pcontacts.core.proton.api.labels.ProtonLabelsApi
import io.pcontacts.core.protoncontacts.CardCryptoOutcome
import io.pcontacts.core.protoncontacts.ContactDecrypter
import io.pcontacts.core.protoncontacts.ContactProcessor
import io.pcontacts.core.storage.db.dao.ContactMapDao
import io.pcontacts.core.storage.db.entity.ContactMapEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Engine test against fakes. Uses CLEAR_TEXT cards so the real
 * ContactProcessor exercises actual ez-vcard parsing without needing
 * real PGP keys. The real-crypto end-to-end path is covered in
 * ContactDecryptBootstrapTest.
 */
class ContactDetailSyncEngineTest {

    private val account = Account("alice@proton.me", "io.pcontacts.account")

    @Test fun first_run_decrypts_writes_inserts_with_FN_from_signed_card_and_email_from_decrypt() = runTest {
        val api = DetailFakeApi(
            metadataPages = listOf(metaPage(meta("c1", 100L))),
            contacts = mapOf(
                "c1" to contact(
                    "c1", 100L, """
                        BEGIN:VCARD
                        VERSION:4.0
                        FN:Alice Doe
                        EMAIL;PREF=1:alice@proton.me
                        END:VCARD
                    """.trimIndent()
                )
            )
        )
        val dao = DetailFakeContactMapDao()
        val applier = DetailFakeApplier(base = 1000L)
        val engine = newEngine(api, dao, applier)

        val report = engine.sync(account)

        assertEquals(
            SyncReport(totalServer = 1, inserted = 1, updated = 0, deleted = 0, unchanged = 0),
            report
        )
        val mapping = dao.snapshot()["c1"]
        assertNotNull(mapping)
        assertEquals(1000L, mapping!!.androidRawContactId)
        assertEquals(100L, mapping.modifyTime)
        assertTrue("CLEAR_TEXT-only cards always verify (no signature path)", mapping.isVerified)

        val createIntent = applier.lastIntents.single() as RawContactOpIntent.CreateContact
        assertEquals("Alice Doe", createIntent.row.displayName)
        assertEquals(listOf("alice@proton.me"), createIntent.row.emails)
    }

    @Test fun second_run_with_unchanged_modifyTime_cheap_skips_without_fetching_contact() = runTest {
        val api = DetailFakeApi(
            metadataPages = listOf(
                metaPage(meta("c1", 100L)),
                metaPage(meta("c1", 100L))   // identical modifyTime — engine must cheap-skip
            ),
            contacts = mapOf(
                "c1" to contact("c1", 100L, """
                    BEGIN:VCARD
                    VERSION:4.0
                    FN:Alice
                    EMAIL:alice@proton.me
                    END:VCARD
                """.trimIndent())
            ),
            repeatContacts = true
        )
        val dao = DetailFakeContactMapDao()
        val applier = DetailFakeApplier(base = 1L)
        val engine = newEngine(api, dao, applier)
        engine.sync(account)
        val firstFetchCount = api.getContactCallCount

        val secondReport = engine.sync(account)

        assertEquals(
            SyncReport(totalServer = 1, inserted = 0, updated = 0, deleted = 0, unchanged = 1),
            secondReport
        )
        // No applier on the second run, AND no getContact call — cheap-skip
        // via modifyTime works without paying the fetch + decrypt cost.
        assertEquals("applier must not be invoked on unchanged second run", 1, applier.applyCallCount)
        assertEquals("getContact must not be called when modifyTime is unchanged",
            firstFetchCount, api.getContactCallCount)
    }

    @Test fun bumped_modifyTime_with_unchanged_content_skips_writes_but_refreshes_mapping() = runTest {
        // Server bumps modifyTime (e.g. a /contacts/label call) but the
        // user-visible content hasn't changed. Engine must fetch (because
        // modifyTime increased) but the hash-skip path must avoid the write.
        val sameVCard = """
            BEGIN:VCARD
            VERSION:4.0
            FN:Alice
            EMAIL:alice@proton.me
            END:VCARD
        """.trimIndent()
        val api = DetailFakeApi(
            metadataPages = listOf(
                metaPage(meta("c1", 100L)),
                metaPage(meta("c1", 200L))   // bumped
            ),
            contacts = mapOf("c1" to contact("c1", 100L, sameVCard)),
            secondRoundContacts = mapOf("c1" to contact("c1", 200L, sameVCard))
        )
        val dao = DetailFakeContactMapDao()
        val applier = DetailFakeApplier(base = 1L)
        val engine = newEngine(api, dao, applier)
        engine.sync(account)

        val secondReport = engine.sync(account)

        assertEquals(0, secondReport.inserted)
        assertEquals(0, secondReport.updated)
        assertEquals("hash matched — no ContactsContract write", 1, applier.applyCallCount)
        // Mapping's modifyTime got refreshed to 200 even though we skipped the write.
        assertEquals(200L, dao.snapshot()["c1"]!!.modifyTime)
    }

    @Test fun content_change_triggers_update_with_same_rawContactId() = runTest {
        val api = DetailFakeApi(
            metadataPages = listOf(
                metaPage(meta("c1", 100L)),
                metaPage(meta("c1", 200L))
            ),
            contacts = mapOf(
                "c1" to contact("c1", 100L, """
                    BEGIN:VCARD
                    VERSION:4.0
                    FN:Alice
                    EMAIL:alice@proton.me
                    END:VCARD
                """.trimIndent())
            ),
            secondRoundContacts = mapOf(
                "c1" to contact("c1", 200L, """
                    BEGIN:VCARD
                    VERSION:4.0
                    FN:Alice Doe
                    EMAIL:alice@proton.me
                    END:VCARD
                """.trimIndent())
            )
        )
        val dao = DetailFakeContactMapDao()
        val applier = DetailFakeApplier(base = 500L)
        val engine = newEngine(api, dao, applier)
        engine.sync(account)

        val secondReport = engine.sync(account)

        assertEquals(1, secondReport.updated)
        val updateIntent = applier.lastIntents.single() as RawContactOpIntent.UpdateContact
        assertEquals("Alice Doe", updateIntent.row.displayName)
        assertEquals(500L, updateIntent.rawContactId)
        assertEquals(200L, dao.snapshot()["c1"]!!.modifyTime)
    }

    @Test fun server_side_deletion_triggers_delete_and_removes_mapping() = runTest {
        val api = DetailFakeApi(
            metadataPages = listOf(
                metaPage(meta("c1", 100L), meta("c2", 100L)),
                metaPage(meta("c1", 100L))     // bob disappears
            ),
            contacts = mapOf(
                "c1" to contact("c1", 100L, """
                    BEGIN:VCARD
                    VERSION:4.0
                    FN:Alice
                    EMAIL:alice@proton.me
                    END:VCARD
                """.trimIndent()),
                "c2" to contact("c2", 100L, """
                    BEGIN:VCARD
                    VERSION:4.0
                    FN:Bob
                    EMAIL:bob@proton.me
                    END:VCARD
                """.trimIndent())
            ),
            repeatContacts = true
        )
        val dao = DetailFakeContactMapDao()
        val applier = DetailFakeApplier(base = 1L)
        val engine = newEngine(api, dao, applier)
        engine.sync(account)
        val report = engine.sync(account)

        assertEquals(1, report.deleted)
        assertNull("bob's mapping must be gone", dao.snapshot()["c2"])
        assertNotNull(dao.snapshot()["c1"])
    }

    @Test fun sync_report_includes_unverified_count() = runTest {
        val api = DetailFakeApi(
            metadataPages = listOf(metaPage(meta("c1", 100L))),
            contacts = mapOf(
                "c1" to ContactDto(
                    id = "c1",
                    modifyTime = 100L,
                    cards = listOf(
                        ContactCardDto(
                            type = 2,
                            data = """
                                BEGIN:VCARD
                                VERSION:4.0
                                FN:Alice
                                EMAIL:alice@proton.me
                                END:VCARD
                            """.trimIndent(),
                            signature = "bad-sig"
                        )
                    )
                )
            )
        )
        val dao = DetailFakeContactMapDao()
        val applier = DetailFakeApplier(base = 1L)
        val rejectingProcessor = ContactProcessor(ContactDecrypter(cryptoOp = { req ->
            CardCryptoOutcome(
                plaintext = (req as? io.pcontacts.core.protoncontacts.CardCryptoRequest.VerifyOnly)?.data ?: "",
                verified = false
            )
        }))
        val engine = ContactDetailSyncEngine(
            metadataPager = ContactsMetadataPager(api = api, pageSize = 1000),
            contactsApi = api,
            labelsApi = NoLabelsApi,
            processor = rejectingProcessor,
            contactMapDao = dao,
            readExisting = { _ -> applier.knownRawIds() },
            applyIntents = { acct, ints -> applier.apply(acct, ints) },
            clock = { 1_700_000_000L }
        )

        val report = engine.sync(account)

        assertEquals(1, report.unverifiedCount)
    }

    @Test fun sync_report_unverified_count_zero_when_all_verified() = runTest {
        val api = DetailFakeApi(
            metadataPages = listOf(metaPage(meta("c1", 100L))),
            contacts = mapOf(
                "c1" to contact("c1", 100L, """
                    BEGIN:VCARD
                    VERSION:4.0
                    FN:Alice
                    EMAIL:alice@proton.me
                    END:VCARD
                """.trimIndent())
            )
        )
        val dao = DetailFakeContactMapDao()
        val applier = DetailFakeApplier(base = 1L)
        val engine = newEngine(api, dao, applier)

        val report = engine.sync(account)

        assertEquals(0, report.unverifiedCount)
    }

    @Test fun signature_failure_propagates_to_is_verified_false() = runTest {
        val api = DetailFakeApi(
            metadataPages = listOf(metaPage(meta("c1", 100L))),
            contacts = mapOf(
                "c1" to ContactDto(
                    id = "c1",
                    modifyTime = 100L,
                    cards = listOf(
                        ContactCardDto(type = 2, data = """
                            BEGIN:VCARD
                            VERSION:4.0
                            FN:Alice
                            EMAIL:alice@proton.me
                            END:VCARD
                        """.trimIndent(), signature = "any-sig")
                    )
                )
            )
        )
        val dao = DetailFakeContactMapDao()
        val applier = DetailFakeApplier(base = 1L)
        val rejectingProcessor = ContactProcessor(ContactDecrypter(cryptoOp = { req ->
            CardCryptoOutcome(
                plaintext = (req as? io.pcontacts.core.protoncontacts.CardCryptoRequest.VerifyOnly)?.data ?: "",
                verified = false
            )
        }))

        val engine = ContactDetailSyncEngine(
            metadataPager = ContactsMetadataPager(api = api, pageSize = 1000),
            contactsApi = api,
            labelsApi = NoLabelsApi,
            processor = rejectingProcessor,
            contactMapDao = dao,
            readExisting = { _ -> applier.knownRawIds() },
            applyIntents = { acct, ints -> applier.apply(acct, ints) },
            clock = { 1_700_000_000L }
        )
        engine.sync(account)

        val mapping = dao.snapshot()["c1"]
        assertNotNull(mapping)
        assertFalse("SIGNED card with failed verification must mark mapping unverified",
            mapping!!.isVerified)
    }

    @Test fun structured_name_pieces_and_phones_project_into_the_create_intent() = runTest {
        val api = DetailFakeApi(
            metadataPages = listOf(metaPage(meta("c1", 100L))),
            contacts = mapOf(
                "c1" to contact(
                    "c1", 100L, """
                        BEGIN:VCARD
                        VERSION:4.0
                        FN:Alice Doe
                        N:Doe;Alice;Marie;Dr;PhD
                        EMAIL:alice@proton.me
                        TEL;TYPE=home:+1 555 0100
                        TEL;TYPE=cell;PREF=1:+1 555 0101
                        TEL;TYPE=fax,work:+1 555 0102
                        END:VCARD
                    """.trimIndent()
                )
            )
        )
        val dao = DetailFakeContactMapDao()
        val applier = DetailFakeApplier(base = 1L)
        val engine = newEngine(api, dao, applier)

        engine.sync(account)

        val createIntent = applier.lastIntents.single() as RawContactOpIntent.CreateContact
        val row = createIntent.row

        assertEquals("Alice Doe", row.displayName)
        // Structured-name pieces collapsed to single columns.
        val sn = row.structuredName
        assertNotNull(sn)
        assertEquals("Alice", sn!!.given)
        assertEquals("Doe", sn.family)
        assertEquals("Marie", sn.middle)
        assertEquals("Dr", sn.prefix)
        assertEquals("PhD", sn.suffix)

        // Phones ordered primary-first (cell with PREF=1 leads).
        assertEquals(3, row.phones.size)
        assertEquals("+1 555 0101", row.phones[0].number)
        assertTrue("the PREF=1 cell entry must surface as primary", row.phones[0].isPrimary)
        assertEquals(io.pcontacts.core.contactswriter.PhoneType.MOBILE, row.phones[0].type)
        // Fax + work maps to FAX_WORK; the home line stays HOME.
        val byNumber = row.phones.associateBy { it.number }
        assertEquals(io.pcontacts.core.contactswriter.PhoneType.HOME, byNumber["+1 555 0100"]!!.type)
        assertEquals(io.pcontacts.core.contactswriter.PhoneType.FAX_WORK, byNumber["+1 555 0102"]!!.type)
    }

    @Test fun full_field_set_projects_addresses_org_notes_im_and_photo_into_the_create_intent() = runTest {
        // Tiny 1×1 transparent PNG so the photo path is self-contained.
        val pngBytes = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
            0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15.toByte(), 0xC4.toByte(),
            0x89.toByte(), 0x00, 0x00, 0x00, 0x0D, 0x49, 0x44, 0x41,
            0x54, 0x78, 0x9C.toByte(), 0x62, 0x00, 0x01, 0x00, 0x00,
            0x05, 0x00, 0x01, 0x0D, 0x0A, 0x2D, 0xB4.toByte(), 0x00,
            0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(),
            0x42, 0x60, 0x82.toByte()
        )
        val photoB64 = java.util.Base64.getEncoder().encodeToString(pngBytes)
        val api = DetailFakeApi(
            metadataPages = listOf(metaPage(meta("c1", 100L))),
            contacts = mapOf(
                "c1" to contact(
                    "c1", 100L, """
                        BEGIN:VCARD
                        VERSION:4.0
                        FN:Alice Doe
                        EMAIL:alice@proton.me
                        ADR;TYPE=home;PREF=1:;;100 Main St;Springfield;IL;62704;USA
                        ADR;TYPE=work:;;200 Office Way;Chicago;IL;60601;USA
                        ORG:Acme Inc.;R&D
                        TITLE:Principal Engineer
                        NOTE:First note
                        NOTE:Second note
                        IMPP:xmpp:alice@chat.example
                        IMPP:matrix:@alice:matrix.example
                        PHOTO:data:image/png;base64,$photoB64
                        END:VCARD
                    """.trimIndent()
                )
            )
        )
        val dao = DetailFakeContactMapDao()
        val applier = DetailFakeApplier(base = 1L)
        val engine = newEngine(api, dao, applier)

        engine.sync(account)

        val createIntent = applier.lastIntents.single() as RawContactOpIntent.CreateContact
        val row = createIntent.row

        // Addresses: 2 entries, primary first, types mapped.
        assertEquals(2, row.addresses.size)
        val home = row.addresses[0]
        assertTrue("PREF=1 ADR must be primary", home.isPrimary)
        assertEquals("100 Main St", home.street)
        assertEquals("Springfield", home.city)
        assertEquals(io.pcontacts.core.contactswriter.PostalAddressType.HOME, home.type)
        assertEquals(
            io.pcontacts.core.contactswriter.PostalAddressType.WORK,
            row.addresses[1].type
        )

        // Organization.
        val org = row.organization
        assertNotNull(org)
        assertEquals("Acme Inc.", org!!.company)
        assertEquals("R&D", org.department)
        assertEquals("Principal Engineer", org.title)

        // Notes.
        assertEquals(listOf("First note", "Second note"), row.notes)

        // IM accounts: xmpp → JABBER (named tier), matrix → CUSTOM with the
        // original scheme as customProtocol label.
        assertEquals(2, row.imAccounts.size)
        val byHandle = row.imAccounts.associateBy { it.handle }
        assertEquals(
            io.pcontacts.core.contactswriter.ImProtocol.JABBER,
            byHandle["alice@chat.example"]!!.protocol
        )
        val matrix = byHandle["@alice:matrix.example"]!!
        assertEquals(io.pcontacts.core.contactswriter.ImProtocol.CUSTOM, matrix.protocol)
        assertEquals("matrix", matrix.customProtocol)

        // Photo round-trips bit-exactly.
        assertNotNull(row.photo)
        assertTrue("photo bytes must survive the projection",
            pngBytes.contentEquals(row.photo!!.data))
    }

    @Test fun fetch_failure_for_one_contact_does_not_abort_the_run() = runTest {
        val api = DetailFakeApi(
            metadataPages = listOf(metaPage(meta("c1", 100L), meta("c2", 100L))),
            contacts = mapOf(
                "c1" to contact("c1", 100L, """
                    BEGIN:VCARD
                    VERSION:4.0
                    FN:Alice
                    EMAIL:a@x
                    END:VCARD
                """.trimIndent())
                // c2 missing → DetailFakeApi.getContact throws
            )
        )
        val dao = DetailFakeContactMapDao()
        val applier = DetailFakeApplier(base = 1L)
        val engine = newEngine(api, dao, applier)

        val report = engine.sync(account)

        assertEquals(1, report.inserted)
        assertEquals("skipped contact reported as failed", 1, report.failed)
        assertNotNull(dao.snapshot()["c1"])
        assertNull(dao.snapshot()["c2"])
    }

    @Test fun decrypt_failure_for_one_contact_is_skipped_and_reported() = runTest {
        val goodVCard = """
            BEGIN:VCARD
            VERSION:4.0
            FN:Alice
            EMAIL:alice@proton.me
            END:VCARD
        """.trimIndent()
        val api = DetailFakeApi(
            metadataPages = listOf(metaPage(meta("c1", 100L), meta("c2", 100L))),
            contacts = mapOf(
                "c1" to ContactDto(
                    id = "c1", modifyTime = 100L,
                    cards = listOf(ContactCardDto(type = 2, data = goodVCard, signature = "sig"))
                ),
                "c2" to ContactDto(
                    id = "c2", modifyTime = 100L,
                    cards = listOf(ContactCardDto(type = 2, data = "BOOM", signature = "sig"))
                )
            )
        )
        val dao = DetailFakeContactMapDao()
        val applier = DetailFakeApplier(base = 1L)
        // cryptoOp throws for the c2 card — the whole run must not abort.
        val processor = ContactProcessor(ContactDecrypter(cryptoOp = { req ->
            val data = (req as? io.pcontacts.core.protoncontacts.CardCryptoRequest.VerifyOnly)?.data ?: ""
            if (data.contains("BOOM")) error("simulated decrypt failure")
            CardCryptoOutcome(plaintext = data, verified = true)
        }))
        val engine = ContactDetailSyncEngine(
            metadataPager = ContactsMetadataPager(api = api, pageSize = 1000),
            contactsApi = api,
            labelsApi = NoLabelsApi,
            processor = processor,
            contactMapDao = dao,
            readExisting = { _ -> applier.knownRawIds() },
            applyIntents = { acct, ints -> applier.apply(acct, ints) },
            clock = { 1_700_000_000L }
        )

        val report = engine.sync(account)

        assertEquals("good contact still synced", 1, report.inserted)
        assertEquals("bad contact counted as failed", 1, report.failed)
        assertNotNull(dao.snapshot()["c1"])
        assertNull(dao.snapshot()["c2"])
    }

    // --- helpers ---

    private fun newEngine(
        api: DetailFakeApi,
        dao: DetailFakeContactMapDao,
        applier: DetailFakeApplier
    ): ContactDetailSyncEngine {
        val processor = ContactProcessor(ContactDecrypter(cryptoOp = { _ ->
            error("CLEAR_TEXT-only cards must not invoke crypto op")
        }))
        return ContactDetailSyncEngine(
            metadataPager = ContactsMetadataPager(api = api, pageSize = 1000),
            contactsApi = api,
            labelsApi = NoLabelsApi,
            processor = processor,
            contactMapDao = dao,
            readExisting = { _ -> applier.knownRawIds() },
            applyIntents = { acct, intents -> applier.apply(acct, intents) },
            clock = { 1_700_000_000L }
        )
    }

    private fun meta(id: String, modifyTime: Long) = ContactMetadataDto(id = id, modifyTime = modifyTime)
    private fun metaPage(vararg rows: ContactMetadataDto) =
        ContactsPageResponse(code = 1000, contacts = rows.toList(), total = rows.size)
    private fun contact(id: String, modifyTime: Long, clearTextVCard: String) = ContactDto(
        id = id,
        modifyTime = modifyTime,
        cards = listOf(ContactCardDto(type = 0, data = clearTextVCard))
    )
}

/**
 * Serves the two endpoints the detail engine uses: listContacts and getContact.
 * `secondRoundContacts` swaps the contact map after the first round; the
 * "round" boundary fires when every contact in the initial map has been
 * fetched at least once.
 */
/** Returns an empty label set for engine tests that don't care about groups. */
private object NoLabelsApi : ProtonLabelsApi {
    override suspend fun listLabels(type: Int): GetLabelsResponse =
        GetLabelsResponse(code = 1000, labels = emptyList())
}

private class DetailFakeApi(
    metadataPages: List<ContactsPageResponse>,
    private val contacts: Map<String, ContactDto>,
    private val secondRoundContacts: Map<String, ContactDto>? = null,
    private val repeatContacts: Boolean = false
) : ProtonContactsApi {
    private val metadataQueue = ArrayDeque(metadataPages)
    private var firstRoundDone = false
    private val firstRoundFetched = HashSet<String>()
    var getContactCallCount = 0
        private set

    override suspend fun listContacts(
        page: Int,
        pageSize: Int,
        labelIdFilter: String?
    ): ContactsPageResponse =
        if (metadataQueue.isEmpty()) ContactsPageResponse(code = 1000) else metadataQueue.removeFirst()

    override suspend fun listContactEmails(
        page: Int,
        pageSize: Int,
        emailFilter: String?,
        labelIdFilter: String?
    ): ContactEmailsPageResponse =
        error("ContactDetailSyncEngine does not use /emails")

    override suspend fun getContact(id: String): GetContactResponse {
        getContactCallCount += 1
        val source = when {
            firstRoundDone && secondRoundContacts != null -> secondRoundContacts
            else -> contacts
        }
        val contact = source[id] ?: error("DetailFakeApi has no fixture for contact id=$id")

        if (!firstRoundDone) {
            firstRoundFetched += id
            if (firstRoundFetched.size == contacts.size) {
                firstRoundDone = true
                if (repeatContacts) firstRoundFetched.clear()
            }
        }
        return GetContactResponse(code = 1000, contact = contact)
    }

    override suspend fun createContacts(request: CreateContactsRequest): CreateContactsResponse =
        error("not used in read-engine tests")

    override suspend fun updateContact(id: String, request: UpdateContactRequest): UpdateContactResponse =
        error("not used in read-engine tests")

    override suspend fun deleteContacts(request: BulkDeleteRequest): BulkDeleteResponse =
        error("not used in read-engine tests")
}

private class DetailFakeContactMapDao : ContactMapDao {
    private val rows = HashMap<String, ContactMapEntity>()
    fun snapshot(): Map<String, ContactMapEntity> = rows.toMap()
    override suspend fun upsert(entry: ContactMapEntity) { rows[entry.protonContactId] = entry }
    override suspend fun upsertAll(entries: List<ContactMapEntity>) {
        entries.forEach { rows[it.protonContactId] = it }
    }
    override suspend fun findByProtonId(id: String) = rows[id]
    override suspend fun findByRawContactId(rawId: Long) =
        rows.values.firstOrNull { it.androidRawContactId == rawId }
    override suspend fun findByProtonUid(uid: String) =
        rows.values.firstOrNull { it.protonUid == uid }
    override suspend fun listLiveProtonIds(): List<String> =
        rows.values.filter { !it.deleted }.map { it.protonContactId }
    override suspend fun listLive(): List<ContactMapEntity> =
        rows.values.filter { !it.deleted }
    override suspend fun countLive(): Int =
        rows.values.count { !it.deleted }
    override suspend fun countUnverified(): Int =
        rows.values.count { !it.deleted && !it.isVerified }
    override suspend fun listUnverified(): List<ContactMapEntity> =
        rows.values.filter { !it.deleted && !it.isVerified }
    override suspend fun markDeleted(id: String) {
        rows[id]?.let { rows[id] = it.copy(deleted = true) }
    }
    override suspend fun listConflicts(): List<ContactMapEntity> =
        rows.values.filter { it.syncStatus == ContactMapEntity.Status.CONFLICT && !it.deleted }
    override suspend fun resolveConflict(id: String) {
        rows[id]?.let { rows[id] = it.copy(syncStatus = ContactMapEntity.Status.CLEAN, lastError = null) }
    }
    override suspend fun maxLastSyncedAt(): Long? =
        rows.values.filter { !it.deleted }.maxOfOrNull { it.lastSyncedAt }
    override suspend fun deleteByProtonId(id: String) { rows.remove(id) }
    override suspend fun deleteAll() { rows.clear() }
}

private class DetailFakeApplier(base: Long) {
    private val sourceIdToRawId = HashMap<String, Long>()
    private var nextId = base
    var applyCallCount = 0
        private set
    var lastIntents: List<RawContactOpIntent> = emptyList()
        private set

    fun knownRawIds(): Map<String, Long> = sourceIdToRawId.toMap()

    suspend fun apply(account: Account, intents: List<RawContactOpIntent>): ApplyResult {
        applyCallCount += 1
        lastIntents = intents
        for (intent in intents) when (intent) {
            is RawContactOpIntent.CreateContact -> sourceIdToRawId[intent.row.sourceId] = nextId++
            is RawContactOpIntent.DeleteContact -> sourceIdToRawId.remove(intent.sourceId)
            is RawContactOpIntent.UpdateContact -> { /* id unchanged */ }
        }
        return ApplyResult(
            insertedContacts = intents.count { it is RawContactOpIntent.CreateContact },
            updatedContacts = intents.count { it is RawContactOpIntent.UpdateContact },
            deletedContacts = intents.count { it is RawContactOpIntent.DeleteContact }
        )
    }
}
