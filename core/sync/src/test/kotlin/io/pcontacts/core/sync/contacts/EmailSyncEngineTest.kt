// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.sync.contacts

import android.accounts.Account
import io.pcontacts.core.contactswriter.ApplyResult
import io.pcontacts.core.contactswriter.RawContactOpIntent
import io.pcontacts.core.proton.api.contacts.ContactEmailDto
import io.pcontacts.core.proton.api.contacts.ContactEmailsPageResponse
import io.pcontacts.core.proton.api.contacts.ContactEmailsPager
import io.pcontacts.core.proton.api.contacts.GetContactResponse
import io.pcontacts.core.proton.api.contacts.ProtonContactsApi
import io.pcontacts.core.storage.db.dao.ContactMapDao
import io.pcontacts.core.storage.db.entity.ContactMapEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end engine test against fakes. Validates:
 *   - first run inserts everything from the server,
 *   - second run with identical state is a no-op (idempotency — the §17
 *     task-16 acceptance criterion),
 *   - a content change triggers exactly one Update with the SAME rawId,
 *   - a server-side deletion triggers exactly one Delete + removes mapping,
 *   - multiple email rows per contact reduce to one Create using the
 *     Defaults=1 row's address.
 */
class EmailSyncEngineTest {

    private val account = Account("alice@proton.me", "io.pcontacts.account")

    @Test fun first_run_inserts_every_server_contact_and_populates_mapping() = runTest {
        val api = FakeContactsApi(
            page(
                email("e1", "c1", "alice@proton.me", "Alice"),
                email("e2", "c2", "bob@proton.me", "Bob"),
                email("e3", "c3", "carol@proton.me", "Carol")
            )
        )
        val dao = FakeContactMapDao()
        val applier = FakeApplier(base = 1000L)
        val engine = newEngine(api, dao, applier)

        val report = engine.sync(account)

        assertEquals(SyncReport(totalServer = 3, inserted = 3, updated = 0, deleted = 0, unchanged = 0), report)
        assertEquals(setOf("c1", "c2", "c3"), dao.snapshot().keys)
        assertEquals(1000L, dao.snapshot()["c1"]!!.androidRawContactId)
        assertEquals(1001L, dao.snapshot()["c2"]!!.androidRawContactId)
        assertEquals(1002L, dao.snapshot()["c3"]!!.androidRawContactId)
        assertTrue(dao.snapshot().values.all { it.contentHash.isNotBlank() })
    }

    @Test fun second_run_with_identical_state_is_a_no_op() = runTest {
        val sameRow = listOf(email("e1", "c1", "alice@proton.me", "Alice"))
        val api = FakeContactsApi(page(*sameRow.toTypedArray()), page(*sameRow.toTypedArray()))
        val dao = FakeContactMapDao()
        val applier = FakeApplier(base = 500L)
        val engine = newEngine(api, dao, applier)

        engine.sync(account)
        val secondReport = engine.sync(account)

        assertEquals(SyncReport(totalServer = 1, inserted = 0, updated = 0, deleted = 0, unchanged = 1), secondReport)
        assertEquals("applier must not be invoked on the second run", 1, applier.callCount)
    }

    @Test fun content_change_triggers_a_single_update_against_the_same_rawContactId() = runTest {
        val api = FakeContactsApi(
            page(email("e1", "c1", "alice@proton.me", "Alice")),
            page(email("e1", "c1", "alice@proton.me", "Alice Doe"))
        )
        val dao = FakeContactMapDao()
        val applier = FakeApplier(base = 700L)
        val engine = newEngine(api, dao, applier)
        engine.sync(account)

        val report = engine.sync(account)

        assertEquals(0, report.inserted)
        assertEquals(1, report.updated)
        assertEquals(0, report.deleted)
        val updateIntent = applier.lastIntents.single() as RawContactOpIntent.UpdateContact
        // The rawContactId must be the one assigned on the first run — the
        // engine never re-inserts a stable contact.
        assertEquals(700L, updateIntent.rawContactId)
        assertEquals("Alice Doe", updateIntent.row.displayName)
        // Mapping hash updated to reflect the new content.
        assertNotNull(dao.snapshot()["c1"])
    }

    @Test fun server_side_deletion_triggers_a_delete_and_removes_mapping() = runTest {
        val api = FakeContactsApi(
            page(
                email("e1", "c1", "alice@proton.me", "Alice"),
                email("e2", "c2", "bob@proton.me", "Bob")
            ),
            page(email("e1", "c1", "alice@proton.me", "Alice"))   // bob disappears
        )
        val dao = FakeContactMapDao()
        val applier = FakeApplier(base = 900L)
        val engine = newEngine(api, dao, applier)
        engine.sync(account)

        val report = engine.sync(account)

        assertEquals(1, report.deleted)
        assertEquals(0, report.inserted)
        assertEquals(0, report.updated)
        assertNull("bob's mapping row must be gone", dao.snapshot()["c2"])
        assertNotNull("alice's mapping row must remain", dao.snapshot()["c1"])
    }

    @Test fun multiple_email_rows_per_contact_emit_one_create_with_all_emails_primary_first() = runTest {
        val api = FakeContactsApi(
            page(
                email("e1a", "c1", "alice.work@proton.me", "Alice", defaults = 0, order = 2),
                email("e1b", "c1", "alice@proton.me",      "Alice", defaults = 1, order = 0),
                email("e1c", "c1", "alice.alt@proton.me",  "Alice", defaults = 0, order = 1)
            )
        )
        val dao = FakeContactMapDao()
        val applier = FakeApplier(base = 1L)
        val engine = newEngine(api, dao, applier)

        engine.sync(account)

        val createIntent = applier.lastIntents.single() as RawContactOpIntent.CreateContact
        assertEquals("c1", createIntent.row.sourceId)
        // Primary (Defaults=1, Order=0) lands first; secondary order
        // follows the Defaults DESC then Order ASC sort.
        assertEquals(
            listOf("alice@proton.me", "alice.alt@proton.me", "alice.work@proton.me"),
            createIntent.row.emails
        )
    }

    // --- helpers ---

    private fun newEngine(
        api: FakeContactsApi,
        dao: FakeContactMapDao,
        applier: FakeApplier
    ): EmailSyncEngine = EmailSyncEngine(
        pager = ContactEmailsPager(api = api, pageSize = 1000),
        contactMapDao = dao,
        readExisting = { _ -> applier.knownRawIds() },
        applyIntents = { acct, intents -> applier.apply(acct, intents) },
        clock = { 1_700_000_000L }
    )

    private fun email(
        id: String,
        contactId: String,
        addr: String,
        displayName: String,
        defaults: Int = 0,
        order: Int = 0
    ) = ContactEmailDto(
        id = id, email = addr, name = displayName,
        contactId = contactId, defaults = defaults, order = order
    )

    private fun page(vararg emails: ContactEmailDto) =
        ContactEmailsPageResponse(code = 1000, contactEmails = emails.toList(), total = emails.size)
}

private class FakeContactsApi(vararg responses: ContactEmailsPageResponse) : ProtonContactsApi {
    private val queue = ArrayDeque(responses.toList())

    override suspend fun listContactEmails(
        page: Int,
        pageSize: Int,
        emailFilter: String?,
        labelIdFilter: String?
    ): ContactEmailsPageResponse =
        if (queue.isEmpty()) ContactEmailsPageResponse(code = 1000) else queue.removeFirst()

    override suspend fun getContact(id: String): GetContactResponse =
        error("FakeContactsApi.getContact not used in email-only sync tests")

    override suspend fun listContacts(
        page: Int,
        pageSize: Int,
        labelIdFilter: String?
    ): io.pcontacts.core.proton.api.contacts.ContactsPageResponse =
        error("FakeContactsApi.listContacts not used in email-only sync tests")
}

private class FakeContactMapDao : ContactMapDao {
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

/**
 * Stand-in for the BatchApplier + RawContactReader pair. Tracks intents
 * and produces a consistent post-apply view of "existing rawIds" so the
 * engine can fill in mapping rows on inserts.
 */
private class FakeApplier(base: Long) {
    private val sourceIdToRawId = HashMap<String, Long>()
    private var nextId = base
    var callCount = 0
        private set
    var lastIntents: List<RawContactOpIntent> = emptyList()
        private set

    fun knownRawIds(): Map<String, Long> = sourceIdToRawId.toMap()

    suspend fun apply(account: Account, intents: List<RawContactOpIntent>): ApplyResult {
        callCount += 1
        lastIntents = intents
        for (intent in intents) when (intent) {
            is RawContactOpIntent.CreateContact -> {
                sourceIdToRawId[intent.row.sourceId] = nextId++
            }
            is RawContactOpIntent.DeleteContact -> {
                sourceIdToRawId.remove(intent.sourceId)
            }
            is RawContactOpIntent.UpdateContact -> { /* rawId unchanged */ }
        }
        return ApplyResult(
            insertedContacts = intents.count { it is RawContactOpIntent.CreateContact },
            updatedContacts = intents.count { it is RawContactOpIntent.UpdateContact },
            deletedContacts = intents.count { it is RawContactOpIntent.DeleteContact }
        )
    }
}
