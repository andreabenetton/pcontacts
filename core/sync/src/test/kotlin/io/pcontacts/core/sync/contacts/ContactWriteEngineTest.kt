// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.sync.contacts

import android.accounts.Account
import io.pcontacts.core.contactswriter.ContactRow
import io.pcontacts.core.contactswriter.DirtyContact
import io.pcontacts.core.proton.api.contacts.BulkDeleteRequest
import io.pcontacts.core.proton.api.contacts.BulkDeleteResponse
import io.pcontacts.core.proton.api.contacts.ContactCardBundle
import io.pcontacts.core.proton.api.contacts.ContactCardDto
import io.pcontacts.core.proton.api.contacts.ContactDto
import io.pcontacts.core.proton.api.contacts.ContactEmailDto
import io.pcontacts.core.proton.api.contacts.ContactEmailsPageResponse
import io.pcontacts.core.proton.api.contacts.ContactsPageResponse
import io.pcontacts.core.proton.api.contacts.CreateContactResponseBody
import io.pcontacts.core.proton.api.contacts.CreateContactResponseItem
import io.pcontacts.core.proton.api.contacts.CreateContactsRequest
import io.pcontacts.core.proton.api.contacts.CreateContactsResponse
import io.pcontacts.core.proton.api.contacts.DeleteResponseBody
import io.pcontacts.core.proton.api.contacts.DeleteResponseItem
import io.pcontacts.core.proton.api.contacts.GetContactResponse
import io.pcontacts.core.proton.api.contacts.ProtonContactsApi
import io.pcontacts.core.proton.api.contacts.UpdateContactRequest
import io.pcontacts.core.proton.api.contacts.UpdateContactResponse
import io.pcontacts.core.protoncontacts.CardEncryptOp
import io.pcontacts.core.protoncontacts.CardEncryptOutcome
import io.pcontacts.core.protoncontacts.CardEncryptRequest
import io.pcontacts.core.protoncontacts.ContactSerializer
import io.pcontacts.core.protoncontacts.DecryptedContact
import io.pcontacts.core.protoncontacts.DecryptedEmail
import io.pcontacts.core.storage.db.dao.ContactMapDao
import io.pcontacts.core.storage.db.dao.OutboxDao
import io.pcontacts.core.storage.db.entity.ContactMapEntity
import io.pcontacts.core.storage.db.entity.OutboxEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class ContactWriteEngineTest {

    private val passThrough: CardEncryptOp = { request ->
        when (request) {
            is CardEncryptRequest.SignOnly ->
                CardEncryptOutcome(data = request.plaintext, signature = "sig-signed")
            is CardEncryptRequest.EncryptAndSign ->
                CardEncryptOutcome(data = request.plaintext, signature = "sig-encrypted")
        }
    }

    private val serializer = ContactSerializer(encryptOp = passThrough)

    private fun sampleContact(id: String) = DecryptedContact(
        protonContactId = id,
        protonUid = null,
        fullName = "Alice",
        emails = listOf(DecryptedEmail("alice@proton.me", isPrimary = true)),
        verified = true,
        cardCount = 2,
        unverifiedCardCount = 0
    )

    @Test fun push_with_empty_outbox_returns_noop_report() = runTest {
        val engine = newEngine()
        val report = engine.push()
        assertTrue(report.isNoOp())
        assertEquals(WriteReport.EMPTY, report)
    }

    @Test fun push_drains_update_entry_and_calls_PUT() = runTest {
        val api = WriteFakeApi()
        val outbox = WriteFakeOutboxDao()
        val contactMap = WriteFakeContactMapDao()
        val contacts = mutableMapOf("ct-1" to sampleContact("ct-1"))

        contactMap.upsert(sampleMapping("ct-1", rawId = 100L))
        outbox.insert(OutboxEntity(
            protonContactId = "ct-1",
            opType = OutboxEntity.OpType.UPDATE,
            payloadHash = "hash-v2",
            createdAt = 1_000_000L
        ))

        val engine = newEngine(api, outbox, contactMap, contacts)
        val report = engine.push()

        assertEquals(1, report.pushed)
        assertEquals(1, report.updated)
        assertEquals(0, report.created)
        assertEquals(0, report.deleted)
        assertEquals("ct-1", api.lastUpdateId)
        assertNotNull(api.lastUpdateRequest)
        assertTrue(outbox.entries.isEmpty())
        val mapping = contactMap.findByProtonId("ct-1")!!
        assertEquals(ContactMapEntity.Status.CLEAN, mapping.syncStatus)
        assertEquals("hash-v2", mapping.lastKnownServerPayloadHash)
    }

    @Test fun push_drains_create_entry_and_calls_POST() = runTest {
        val api = WriteFakeApi().apply {
            createResponse = CreateContactsResponse(
                code = 1000,
                responses = listOf(
                    CreateContactResponseItem(
                        index = 0,
                        response = CreateContactResponseBody(
                            code = 1000,
                            contact = ContactDto(
                                id = "server-ct-1",
                                uid = "server-uid-1",
                                cards = emptyList()
                            )
                        )
                    )
                )
            )
        }
        val outbox = WriteFakeOutboxDao()
        val contactMap = WriteFakeContactMapDao()
        val contacts = mutableMapOf("local-ct-1" to sampleContact("local-ct-1"))

        contactMap.upsert(sampleMapping("local-ct-1", rawId = 200L))
        outbox.insert(OutboxEntity(
            protonContactId = "local-ct-1",
            opType = OutboxEntity.OpType.CREATE,
            payloadHash = "hash-new",
            createdAt = 1_000_000L
        ))

        val engine = newEngine(api, outbox, contactMap, contacts)
        val report = engine.push()

        assertEquals(1, report.pushed)
        assertEquals(1, report.created)
        assertTrue(api.lastCreateRequest != null)
        assertTrue(outbox.entries.isEmpty())
        assertNull(contactMap.findByProtonId("local-ct-1"))
        val serverMapping = contactMap.findByProtonId("server-ct-1")
        assertNotNull(serverMapping)
        assertEquals("server-uid-1", serverMapping!!.protonUid)
    }

    @Test fun push_respects_delete_grace_period() = runTest {
        val api = WriteFakeApi()
        val outbox = WriteFakeOutboxDao()
        val contactMap = WriteFakeContactMapDao()

        contactMap.upsert(sampleMapping("ct-1", rawId = 100L))
        outbox.insert(OutboxEntity(
            protonContactId = "ct-1",
            opType = OutboxEntity.OpType.DELETE,
            payloadHash = "hash-del",
            createdAt = 1_700_000_000L
        ))

        val engine = newEngine(
            api, outbox, contactMap,
            clock = { 1_700_000_000L + 1_000L }
        )
        val report = engine.push()

        assertEquals(1, report.skippedGrace)
        assertEquals(0, report.deleted)
        assertNull(api.lastDeleteRequest)
        assertEquals(1, outbox.entries.size)
    }

    @Test fun push_executes_delete_after_grace_period() = runTest {
        val api = WriteFakeApi()
        val outbox = WriteFakeOutboxDao()
        val contactMap = WriteFakeContactMapDao()

        contactMap.upsert(sampleMapping("ct-1", rawId = 100L))
        outbox.insert(OutboxEntity(
            protonContactId = "ct-1",
            opType = OutboxEntity.OpType.DELETE,
            payloadHash = "hash-del",
            createdAt = 1_700_000_000L
        ))

        val engine = newEngine(
            api, outbox, contactMap,
            clock = { 1_700_000_000L + ContactWriteEngine.GRACE_PERIOD_MS + 1L }
        )
        val report = engine.push()

        assertEquals(1, report.pushed)
        assertEquals(1, report.deleted)
        assertNotNull(api.lastDeleteRequest)
        assertTrue(outbox.entries.isEmpty())
        assertNull(contactMap.findByProtonId("ct-1"))
    }

    @Test fun push_records_failure_with_backoff_on_5xx() = runTest {
        val api = WriteFakeApi().apply { failWith = http(503) }
        val outbox = WriteFakeOutboxDao()
        val contactMap = WriteFakeContactMapDao()
        val contacts = mutableMapOf("ct-1" to sampleContact("ct-1"))

        contactMap.upsert(sampleMapping("ct-1", rawId = 100L))
        outbox.insert(OutboxEntity(
            protonContactId = "ct-1",
            opType = OutboxEntity.OpType.UPDATE,
            payloadHash = "hash-v2",
            createdAt = 1_000_000L
        ))

        val engine = newEngine(api, outbox, contactMap, contacts, clock = { 2_000_000_000L })
        val report = engine.push()

        assertEquals(1, report.failed)
        assertEquals(0, report.quarantined)
        val entry = outbox.entries.values.single()
        assertEquals(1, entry.attempts)
        assertTrue(entry.nextAttemptAt > 2_000_000_000L)
    }

    @Test fun push_quarantines_on_4xx() = runTest {
        val api = WriteFakeApi().apply { failWith = http(400) }
        val outbox = WriteFakeOutboxDao()
        val contactMap = WriteFakeContactMapDao()
        val contacts = mutableMapOf("ct-1" to sampleContact("ct-1"))

        contactMap.upsert(sampleMapping("ct-1", rawId = 100L))
        outbox.insert(OutboxEntity(
            protonContactId = "ct-1",
            opType = OutboxEntity.OpType.UPDATE,
            payloadHash = "hash-v2",
            createdAt = 1_000_000L
        ))

        val engine = newEngine(api, outbox, contactMap, contacts)
        val report = engine.push()

        assertEquals(0, report.failed)
        assertEquals(1, report.quarantined)
        val entry = outbox.entries.values.single()
        assertTrue(entry.quarantined)
    }

    @Test fun push_quarantines_when_local_contact_not_found() = runTest {
        val outbox = WriteFakeOutboxDao()
        val contactMap = WriteFakeContactMapDao()

        contactMap.upsert(sampleMapping("ct-1", rawId = 100L))
        outbox.insert(OutboxEntity(
            protonContactId = "ct-1",
            opType = OutboxEntity.OpType.UPDATE,
            payloadHash = "hash-v2",
            createdAt = 1_000_000L
        ))

        val engine = newEngine(outbox = outbox, contactMap = contactMap)
        val report = engine.push()

        assertEquals(1, report.quarantined)
        assertTrue(outbox.entries.values.single().quarantined)
    }

    @Test fun push_treats_429_as_transient() = runTest {
        val api = WriteFakeApi().apply { failWith = http(429) }
        val outbox = WriteFakeOutboxDao()
        val contactMap = WriteFakeContactMapDao()
        val contacts = mutableMapOf("ct-1" to sampleContact("ct-1"))

        contactMap.upsert(sampleMapping("ct-1", rawId = 100L))
        outbox.insert(OutboxEntity(
            protonContactId = "ct-1",
            opType = OutboxEntity.OpType.UPDATE,
            payloadHash = "hash-v2",
            createdAt = 1_000_000L
        ))

        val engine = newEngine(api, outbox, contactMap, contacts)
        val report = engine.push()

        assertEquals(1, report.failed)
        assertEquals(0, report.quarantined)
    }

    // --- detectChanges tests ---

    private val testAccount = Account("test@proton.me", "io.pcontacts")

    private fun sampleRow(sourceId: String, email: String = "alice@proton.me") =
        ContactRow(
            sourceId = sourceId,
            displayName = "Alice",
            emails = listOf(email)
        )

    @Test fun detectChanges_with_no_dirty_contacts_returns_zero() = runTest {
        val engine = newEngine()
        assertEquals(0, engine.detectChanges(testAccount))
    }

    @Test fun detectChanges_enqueues_update_for_dirty_contact_with_changed_hash() = runTest {
        val outbox = WriteFakeOutboxDao()
        val contactMap = WriteFakeContactMapDao()
        val rows = mutableMapOf(100L to sampleRow("ct-1", email = "alice-new@proton.me"))
        val cleared = mutableListOf<Long>()

        contactMap.upsert(sampleMapping("ct-1", rawId = 100L))

        val engine = newEngine(
            outbox = outbox,
            contactMap = contactMap,
            dirtyContacts = listOf(DirtyContact(100L, "ct-1", isDirty = true, isDeleted = false)),
            contactRows = rows,
            clearedFlags = cleared
        )
        val count = engine.detectChanges(testAccount)

        assertEquals(1, count)
        val entry = outbox.entries.values.single()
        assertEquals(OutboxEntity.OpType.UPDATE, entry.opType)
        assertEquals("ct-1", entry.protonContactId)
        assertTrue(cleared.contains(100L))
    }

    @Test fun detectChanges_skips_update_when_hash_matches() = runTest {
        val outbox = WriteFakeOutboxDao()
        val contactMap = WriteFakeContactMapDao()
        val row = sampleRow("ct-1")
        val hash = EmailSyncHash.compute(row)
        val rows = mutableMapOf(100L to row)

        contactMap.upsert(sampleMapping("ct-1", rawId = 100L).copy(contentHash = hash))

        val engine = newEngine(
            outbox = outbox,
            contactMap = contactMap,
            dirtyContacts = listOf(DirtyContact(100L, "ct-1", isDirty = true, isDeleted = false)),
            contactRows = rows
        )
        val count = engine.detectChanges(testAccount)

        assertEquals(0, count)
        assertTrue(outbox.entries.isEmpty())
    }

    @Test fun detectChanges_enqueues_delete_for_deleted_contact() = runTest {
        val outbox = WriteFakeOutboxDao()
        val contactMap = WriteFakeContactMapDao()
        val cleared = mutableListOf<Long>()

        contactMap.upsert(sampleMapping("ct-1", rawId = 100L))

        val engine = newEngine(
            outbox = outbox,
            contactMap = contactMap,
            dirtyContacts = listOf(DirtyContact(100L, "ct-1", isDirty = false, isDeleted = true)),
            clearedFlags = cleared
        )
        val count = engine.detectChanges(testAccount)

        assertEquals(1, count)
        val entry = outbox.entries.values.single()
        assertEquals(OutboxEntity.OpType.DELETE, entry.opType)
        assertEquals("ct-1", entry.protonContactId)
        assertTrue(cleared.contains(100L))
    }

    @Test fun detectChanges_enqueues_create_for_new_local_contact() = runTest {
        val outbox = WriteFakeOutboxDao()
        val rows = mutableMapOf(500L to sampleRow("local-500"))
        val cleared = mutableListOf<Long>()

        val engine = newEngine(
            outbox = outbox,
            dirtyContacts = listOf(DirtyContact(500L, null, isDirty = true, isDeleted = false)),
            contactRows = rows,
            clearedFlags = cleared
        )
        val count = engine.detectChanges(testAccount)

        assertEquals(1, count)
        val entry = outbox.entries.values.single()
        assertEquals(OutboxEntity.OpType.CREATE, entry.opType)
        assertEquals("local-500", entry.protonContactId)
        assertTrue(cleared.contains(500L))
    }

    @Test fun detectChanges_skips_duplicate_delete() = runTest {
        val outbox = WriteFakeOutboxDao()
        val contactMap = WriteFakeContactMapDao()
        contactMap.upsert(sampleMapping("ct-1", rawId = 100L))

        outbox.insert(OutboxEntity(
            protonContactId = "ct-1",
            opType = OutboxEntity.OpType.DELETE,
            payloadHash = "",
            createdAt = 1_000_000L
        ))

        val engine = newEngine(
            outbox = outbox,
            contactMap = contactMap,
            dirtyContacts = listOf(DirtyContact(100L, "ct-1", isDirty = false, isDeleted = true))
        )
        val count = engine.detectChanges(testAccount)

        assertEquals(0, count)
        assertEquals(1, outbox.entries.size)
    }

    @Test fun detectChanges_skips_duplicate_update_with_same_hash() = runTest {
        val outbox = WriteFakeOutboxDao()
        val contactMap = WriteFakeContactMapDao()
        val row = sampleRow("ct-1", email = "new@proton.me")
        val hash = EmailSyncHash.compute(row)
        val rows = mutableMapOf(100L to row)

        contactMap.upsert(sampleMapping("ct-1", rawId = 100L))
        outbox.insert(OutboxEntity(
            protonContactId = "ct-1",
            opType = OutboxEntity.OpType.UPDATE,
            payloadHash = hash,
            createdAt = 1_000_000L
        ))

        val engine = newEngine(
            outbox = outbox,
            contactMap = contactMap,
            dirtyContacts = listOf(DirtyContact(100L, "ct-1", isDirty = true, isDeleted = false)),
            contactRows = rows
        )
        val count = engine.detectChanges(testAccount)

        assertEquals(0, count)
        assertEquals(1, outbox.entries.size)
    }

    @Test fun detectChanges_skips_when_contact_row_unreadable() = runTest {
        val outbox = WriteFakeOutboxDao()
        val contactMap = WriteFakeContactMapDao()
        contactMap.upsert(sampleMapping("ct-1", rawId = 100L))

        val engine = newEngine(
            outbox = outbox,
            contactMap = contactMap,
            dirtyContacts = listOf(DirtyContact(100L, "ct-1", isDirty = true, isDeleted = false)),
            contactRows = emptyMap()
        )
        val count = engine.detectChanges(testAccount)

        assertEquals(0, count)
        assertTrue(outbox.entries.isEmpty())
    }

    // --- conflict integration tests ---

    @Test fun push_no_conflict_when_server_matches_local() = runTest {
        val api = WriteFakeApi()
        val outbox = WriteFakeOutboxDao()
        val contactMap = WriteFakeContactMapDao()

        val localContact = sampleContact("ct-1").copy(fullName = "Alice Updated")
        val serverContact = sampleContact("ct-1").copy(fullName = "Alice Updated")

        contactMap.upsert(sampleMapping("ct-1", rawId = 100L).copy(
            lastKnownServerPayloadHash = "old-hash"
        ))
        outbox.insert(OutboxEntity(
            protonContactId = "ct-1",
            opType = OutboxEntity.OpType.UPDATE,
            payloadHash = "hash-v2",
            createdAt = 1_000_000L
        ))

        val engine = newEngine(
            api, outbox, contactMap,
            contacts = mapOf("ct-1" to localContact),
            serverContacts = mapOf("ct-1" to serverContact)
        )
        val report = engine.push()

        assertEquals(1, report.pushed)
        assertEquals(1, report.updated)
        assertEquals(0, report.conflicted)
    }

    @Test fun push_detects_conflict_and_marks_mapping() = runTest {
        val api = WriteFakeApi()
        val outbox = WriteFakeOutboxDao()
        val contactMap = WriteFakeContactMapDao()

        val baseContact = sampleContact("ct-1")
        val localContact = sampleContact("ct-1").copy(fullName = "Alice Local")
        val serverContact = sampleContact("ct-1").copy(fullName = "Alice Server")

        contactMap.upsert(sampleMapping("ct-1", rawId = 100L).copy(
            lastKnownServerPayloadHash = "old-hash"
        ))
        outbox.insert(OutboxEntity(
            protonContactId = "ct-1",
            opType = OutboxEntity.OpType.UPDATE,
            payloadHash = "hash-v2",
            createdAt = 1_000_000L
        ))

        val engine = newEngine(
            api, outbox, contactMap,
            contacts = mapOf("ct-1" to localContact),
            serverContacts = mapOf("ct-1" to serverContact)
        )
        val report = engine.push()

        assertEquals(0, report.pushed)
        assertEquals(1, report.conflicted)
        val mapping = contactMap.findByProtonId("ct-1")!!
        assertEquals(ContactMapEntity.Status.CONFLICT, mapping.syncStatus)
        assertTrue(mapping.lastError!!.contains("fullName"))
    }

    @Test fun push_skips_merge_when_no_server_payload_hash() = runTest {
        val api = WriteFakeApi()
        val outbox = WriteFakeOutboxDao()
        val contactMap = WriteFakeContactMapDao()

        val localContact = sampleContact("ct-1").copy(fullName = "Alice Updated")

        contactMap.upsert(sampleMapping("ct-1", rawId = 100L))
        outbox.insert(OutboxEntity(
            protonContactId = "ct-1",
            opType = OutboxEntity.OpType.UPDATE,
            payloadHash = "hash-v2",
            createdAt = 1_000_000L
        ))

        val engine = newEngine(
            api, outbox, contactMap,
            contacts = mapOf("ct-1" to localContact),
            serverContacts = mapOf("ct-1" to sampleContact("ct-1").copy(fullName = "Alice Server"))
        )
        val report = engine.push()

        assertEquals(1, report.pushed)
        assertEquals(0, report.conflicted)
    }

    @Test fun detectChanges_cancels_pending_delete_when_contact_updated() = runTest {
        val outbox = WriteFakeOutboxDao()
        val contactMap = WriteFakeContactMapDao()
        val row = sampleRow("ct-1", email = "alice-updated@proton.me")
        val rows = mutableMapOf(100L to row)

        contactMap.upsert(sampleMapping("ct-1", rawId = 100L))
        outbox.insert(OutboxEntity(
            protonContactId = "ct-1",
            opType = OutboxEntity.OpType.DELETE,
            payloadHash = "",
            createdAt = 1_000_000L
        ))

        val engine = newEngine(
            outbox = outbox,
            contactMap = contactMap,
            dirtyContacts = listOf(DirtyContact(100L, "ct-1", isDirty = true, isDeleted = false)),
            contactRows = rows
        )
        val count = engine.detectChanges(testAccount)

        assertEquals(1, count)
        val entries = outbox.entries.values.toList()
        assertEquals(1, entries.size)
        assertEquals(OutboxEntity.OpType.UPDATE, entries[0].opType)
    }

    @Test fun detectChanges_clears_dirty_flag_even_when_skipped() = runTest {
        val outbox = WriteFakeOutboxDao()
        val contactMap = WriteFakeContactMapDao()
        val row = sampleRow("ct-1")
        val hash = EmailSyncHash.compute(row)
        val rows = mutableMapOf(100L to row)
        val cleared = mutableListOf<Long>()

        contactMap.upsert(sampleMapping("ct-1", rawId = 100L).copy(contentHash = hash))

        val engine = newEngine(
            outbox = outbox,
            contactMap = contactMap,
            dirtyContacts = listOf(DirtyContact(100L, "ct-1", isDirty = true, isDeleted = false)),
            contactRows = rows,
            clearedFlags = cleared
        )
        engine.detectChanges(testAccount)

        assertTrue(cleared.contains(100L))
    }

    // --- helpers ---

    private fun newEngine(
        api: WriteFakeApi = WriteFakeApi(),
        outbox: WriteFakeOutboxDao = WriteFakeOutboxDao(),
        contactMap: WriteFakeContactMapDao = WriteFakeContactMapDao(),
        contacts: Map<String, DecryptedContact> = emptyMap(),
        dirtyContacts: List<DirtyContact> = emptyList(),
        contactRows: Map<Long, ContactRow> = emptyMap(),
        clearedFlags: MutableList<Long>? = null,
        serverContacts: Map<String, DecryptedContact> = emptyMap(),
        clock: () -> Long = { 2_000_000_000L }
    ) = ContactWriteEngine(
        contactsApi = api,
        serializer = serializer,
        outboxDao = outbox,
        contactMapDao = contactMap,
        readLocalContact = { id -> contacts[id] },
        readDirtyContacts = { dirtyContacts },
        readContactRow = { rawId, sourceId -> contactRows[rawId] },
        clearDirtyFlag = { _, rawId -> clearedFlags?.add(rawId) },
        fetchServerContact = { id -> serverContacts[id] },
        clock = clock
    )

    private fun sampleMapping(id: String, rawId: Long) = ContactMapEntity(
        protonContactId = id,
        protonUid = null,
        androidRawContactId = rawId,
        modifyTime = 1_700_000_000L,
        contentHash = "hash-v1",
        isVerified = true,
        deleted = false,
        syncStatus = ContactMapEntity.Status.CLEAN,
        lastError = null,
        lastSyncedAt = 1_700_000_001L
    )

    private fun http(code: Int): HttpException =
        HttpException(Response.error<Unit>(code, okhttp3.ResponseBody.Companion.create(null, "")))
}

// --- fakes ---

private class WriteFakeApi : ProtonContactsApi {
    var failWith: Exception? = null
    var lastUpdateId: String? = null
    var lastUpdateRequest: UpdateContactRequest? = null
    var lastCreateRequest: CreateContactsRequest? = null
    var lastDeleteRequest: BulkDeleteRequest? = null
    var createResponse = CreateContactsResponse(code = 1000)

    override suspend fun listContactEmails(page: Int, pageSize: Int, emailFilter: String?, labelIdFilter: String?) =
        ContactEmailsPageResponse(code = 1000)
    override suspend fun getContact(id: String) =
        error("not used in write engine tests")
    override suspend fun listContacts(page: Int, pageSize: Int, labelIdFilter: String?) =
        ContactsPageResponse(code = 1000)

    override suspend fun createContacts(request: CreateContactsRequest): CreateContactsResponse {
        failWith?.let { throw it }
        lastCreateRequest = request
        return createResponse
    }

    override suspend fun updateContact(id: String, request: UpdateContactRequest): UpdateContactResponse {
        failWith?.let { throw it }
        lastUpdateId = id
        lastUpdateRequest = request
        return UpdateContactResponse(code = 1000)
    }

    override suspend fun deleteContacts(request: BulkDeleteRequest): BulkDeleteResponse {
        failWith?.let { throw it }
        lastDeleteRequest = request
        return BulkDeleteResponse(
            code = 1000,
            responses = request.ids.map { DeleteResponseItem(it, DeleteResponseBody(code = 1000)) }
        )
    }
}

private class WriteFakeOutboxDao : OutboxDao {
    val entries = LinkedHashMap<Long, OutboxEntity>()
    private var nextId = 1L

    override suspend fun insert(entry: OutboxEntity): Long {
        val id = nextId++
        entries[id] = entry.copy(id = id)
        return id
    }

    override suspend fun listReady(now: Long): List<OutboxEntity> =
        entries.values.filter { !it.quarantined && it.nextAttemptAt <= now }
            .sortedBy { it.createdAt }

    override suspend fun findByContact(contactId: String): List<OutboxEntity> =
        entries.values.filter { it.protonContactId == contactId }

    override suspend fun recordFailure(id: Long, attempts: Int, error: String?, nextAt: Long) {
        entries[id]?.let {
            entries[id] = it.copy(attempts = attempts, lastError = error, nextAttemptAt = nextAt)
        }
    }

    override suspend fun quarantine(id: Long, error: String?) {
        entries[id]?.let {
            entries[id] = it.copy(quarantined = true, lastError = error)
        }
    }

    override suspend fun deleteById(id: Long) { entries.remove(id) }
    override suspend fun deleteByContact(contactId: String) {
        entries.entries.removeIf { it.value.protonContactId == contactId }
    }
    override suspend fun deleteAll() { entries.clear() }
    override suspend fun countPending(): Int = entries.values.count { !it.quarantined }
    override suspend fun countQuarantined(): Int = entries.values.count { it.quarantined }
    override suspend fun listPendingDeletes(): List<OutboxEntity> =
        entries.values.filter { it.opType == OutboxEntity.OpType.DELETE && !it.quarantined }
}

private class WriteFakeContactMapDao : ContactMapDao {
    private val rows = HashMap<String, ContactMapEntity>()

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
    override suspend fun countLive(): Int = rows.values.count { !it.deleted }
    override suspend fun countUnverified(): Int =
        rows.values.count { !it.deleted && !it.isVerified }
    override suspend fun markDeleted(id: String) {
        rows[id]?.let { rows[id] = it.copy(deleted = true) }
    }
    override suspend fun listConflicts(): List<ContactMapEntity> =
        rows.values.filter { it.syncStatus == ContactMapEntity.Status.CONFLICT && !it.deleted }
    override suspend fun resolveConflict(id: String) {
        rows[id]?.let { rows[id] = it.copy(syncStatus = ContactMapEntity.Status.CLEAN, lastError = null) }
    }
    override suspend fun deleteByProtonId(id: String) { rows.remove(id) }
    override suspend fun deleteAll() { rows.clear() }
}
