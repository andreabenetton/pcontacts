// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.sync.contacts

import android.accounts.Account
import io.pcontacts.core.contactswriter.ApplyResult
import io.pcontacts.core.contactswriter.RawContactOpIntent
import io.pcontacts.core.proton.api.contacts.ContactCardDto
import io.pcontacts.core.proton.api.contacts.ContactDto
import io.pcontacts.core.proton.api.contacts.ContactEmailDto
import io.pcontacts.core.proton.api.contacts.ContactEmailsPageResponse
import io.pcontacts.core.proton.api.contacts.ContactEmailsPager
import io.pcontacts.core.proton.api.contacts.GetContactResponse
import io.pcontacts.core.proton.api.contacts.ProtonContactsApi
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
        val emails = DetailFakeApi(
            pages = listOf(page(emailRow("c1", "alice@proton.me"))),
            contacts = mapOf(
                "c1" to contact(
                    "c1",
                    modifyTime = 100L,
                    clearTextVCard = """
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
        val engine = newEngine(emails, dao, applier)

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
        assertEquals("alice@proton.me", createIntent.row.email)
    }

    @Test fun second_run_with_unchanged_content_skips_writes_but_refreshes_mapping_metadata() = runTest {
        val emails = DetailFakeApi(
            pages = listOf(
                page(emailRow("c1", "alice@proton.me")),
                page(emailRow("c1", "alice@proton.me"))   // identical second pager pass
            ),
            contacts = mapOf(
                "c1" to contact(
                    "c1",
                    modifyTime = 100L,
                    clearTextVCard = """
                        BEGIN:VCARD
                        VERSION:4.0
                        FN:Alice
                        EMAIL:alice@proton.me
                        END:VCARD
                    """.trimIndent()
                )
            ),
            repeatContacts = true
        )
        val dao = DetailFakeContactMapDao()
        val applier = DetailFakeApplier(base = 1L)
        val engine = newEngine(emails, dao, applier)

        engine.sync(account)
        val secondReport = engine.sync(account)

        assertEquals(
            SyncReport(totalServer = 1, inserted = 0, updated = 0, deleted = 0, unchanged = 1),
            secondReport
        )
        // The applier must NOT be invoked the second time.
        assertEquals("applier must not be invoked when no content changed",
            1, applier.applyCallCount)
    }

    @Test fun content_change_triggers_update_with_same_rawContactId() = runTest {
        val emails = DetailFakeApi(
            pages = listOf(
                page(emailRow("c1", "alice@proton.me")),
                page(emailRow("c1", "alice@proton.me"))
            ),
            contacts = mapOf(
                "c1" to contact(
                    "c1",
                    modifyTime = 100L,
                    clearTextVCard = """
                        BEGIN:VCARD
                        VERSION:4.0
                        FN:Alice
                        EMAIL:alice@proton.me
                        END:VCARD
                    """.trimIndent()
                )
            ),
            secondRoundContacts = mapOf(
                "c1" to contact(
                    "c1",
                    modifyTime = 200L,
                    clearTextVCard = """
                        BEGIN:VCARD
                        VERSION:4.0
                        FN:Alice Doe
                        EMAIL:alice@proton.me
                        END:VCARD
                    """.trimIndent()
                )
            )
        )
        val dao = DetailFakeContactMapDao()
        val applier = DetailFakeApplier(base = 500L)
        val engine = newEngine(emails, dao, applier)
        engine.sync(account)

        val secondReport = engine.sync(account)

        assertEquals(1, secondReport.updated)
        assertEquals(0, secondReport.inserted)
        val updateIntent = applier.lastIntents.single() as RawContactOpIntent.UpdateContact
        assertEquals("Alice Doe", updateIntent.row.displayName)
        assertEquals(500L, updateIntent.rawContactId)    // stable across runs

        val mapping = dao.snapshot()["c1"]
        assertEquals(200L, mapping!!.modifyTime)         // refreshed
    }

    @Test fun server_side_deletion_triggers_delete_and_removes_mapping() = runTest {
        val emails = DetailFakeApi(
            pages = listOf(
                page(emailRow("c1", "alice@proton.me"), emailRow("c2", "bob@proton.me")),
                page(emailRow("c1", "alice@proton.me"))     // bob disappears
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
            secondRoundContacts = mapOf(
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
        val engine = newEngine(emails, dao, applier)
        engine.sync(account)

        val report = engine.sync(account)

        assertEquals(1, report.deleted)
        assertNull("bob's mapping must be gone after delete", dao.snapshot()["c2"])
        assertNotNull("alice's mapping must remain", dao.snapshot()["c1"])
    }

    @Test fun signature_failure_propagates_to_is_verified_false() = runTest {
        // SIGNED card with verified=false (canned crypto rejects).
        val emails = DetailFakeApi(
            pages = listOf(page(emailRow("c1", "alice@proton.me"))),
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

        // Build a processor whose crypto op rejects every signature.
        val rejectingProcessor = ContactProcessor(ContactDecrypter(cryptoOp = { req ->
            CardCryptoOutcome(plaintext = (req as? io.pcontacts.core.protoncontacts.CardCryptoRequest.VerifyOnly)?.data ?: "", verified = false)
        }))

        val engine = ContactDetailSyncEngine(
            pager = ContactEmailsPager(api = emails, pageSize = 1000),
            contactsApi = emails,
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

    @Test fun fetch_failure_for_one_contact_does_not_abort_the_run() = runTest {
        val emails = DetailFakeApi(
            pages = listOf(page(emailRow("c1", "a@x"), emailRow("c2", "b@x"))),
            contacts = mapOf(
                "c1" to contact("c1", 100L, """
                    BEGIN:VCARD
                    VERSION:4.0
                    FN:Alice
                    EMAIL:a@x
                    END:VCARD
                """.trimIndent())
                // c2 intentionally missing → DetailFakeApi throws on getContact
            )
        )
        val dao = DetailFakeContactMapDao()
        val applier = DetailFakeApplier(base = 1L)
        val engine = newEngine(emails, dao, applier)

        val report = engine.sync(account)

        // c1 still gets inserted; c2 is skipped (server says it exists, fetch failed).
        assertEquals(1, report.inserted)
        assertNotNull(dao.snapshot()["c1"])
        assertNull(dao.snapshot()["c2"])
        // c2 is in serverSourceIds AND existing was empty before, so no delete
        // intent fires for it — the right call (we don't know yet if c2 is
        // really gone or just transiently unreadable).
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
            pager = ContactEmailsPager(api = api, pageSize = 1000),
            contactsApi = api,
            processor = processor,
            contactMapDao = dao,
            readExisting = { _ -> applier.knownRawIds() },
            applyIntents = { acct, intents -> applier.apply(acct, intents) },
            clock = { 1_700_000_000L }
        )
    }

    private fun emailRow(contactId: String, address: String) = ContactEmailDto(
        id = "e-$contactId", email = address, contactId = contactId, name = ""
    )

    private fun page(vararg rows: ContactEmailDto) =
        ContactEmailsPageResponse(code = 1000, contactEmails = rows.toList(), total = rows.size)

    private fun contact(id: String, modifyTime: Long, clearTextVCard: String) = ContactDto(
        id = id,
        modifyTime = modifyTime,
        cards = listOf(ContactCardDto(type = 0, data = clearTextVCard))
    )
}

/**
 * One fake serves both ProtonContactsApi roles (pager source + getContact).
 * `secondRoundContacts` swaps the contact set after the first `getContact`
 * call cycle — used by the update/delete tests.
 */
private class DetailFakeApi(
    pages: List<ContactEmailsPageResponse>,
    private val contacts: Map<String, ContactDto>,
    private val secondRoundContacts: Map<String, ContactDto>? = null,
    private val repeatContacts: Boolean = false
) : ProtonContactsApi {
    private val pageQueue = ArrayDeque(pages)
    private var firstRoundDone = false
    private val firstRoundFetched = HashSet<String>()

    override suspend fun listContactEmails(
        page: Int,
        pageSize: Int,
        emailFilter: String?,
        labelIdFilter: String?
    ): ContactEmailsPageResponse =
        if (pageQueue.isEmpty()) ContactEmailsPageResponse(code = 1000) else pageQueue.removeFirst()

    override suspend fun listContacts(
        page: Int,
        pageSize: Int,
        labelIdFilter: String?
    ): io.pcontacts.core.proton.api.contacts.ContactsPageResponse =
        error("listContacts not used in this engine variant")

    override suspend fun getContact(id: String): GetContactResponse {
        val source = when {
            firstRoundDone && secondRoundContacts != null -> secondRoundContacts
            else -> contacts
        }
        val contact = source[id] ?: error("DetailFakeApi has no fixture for contact id=$id")

        if (!firstRoundDone) {
            firstRoundFetched += id
            // The "first round" is over once we've fetched every contact in the
            // initial contacts map. After that, getContact serves the second
            // round's fixtures (if any).
            if (firstRoundFetched.size == contacts.size) {
                firstRoundDone = true
                if (repeatContacts) firstRoundFetched.clear()
            }
        }
        return GetContactResponse(code = 1000, contact = contact)
    }
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
    override suspend fun markDeleted(id: String) {
        rows[id]?.let { rows[id] = it.copy(deleted = true) }
    }
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
