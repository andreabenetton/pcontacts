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
import io.pcontacts.core.proton.api.contacts.ContactMetadataDto
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
import io.pcontacts.core.protoncontacts.CardCryptoOutcome
import io.pcontacts.core.protoncontacts.CardCryptoRequest
import io.pcontacts.core.protoncontacts.CardEncryptOp
import io.pcontacts.core.protoncontacts.CardEncryptOutcome
import io.pcontacts.core.protoncontacts.CardEncryptRequest
import io.pcontacts.core.protoncontacts.CardType
import io.pcontacts.core.protoncontacts.ContactDecrypter
import io.pcontacts.core.protoncontacts.ContactProcessor
import io.pcontacts.core.protoncontacts.ContactSerializer
import io.pcontacts.core.protoncontacts.DecryptedContact
import io.pcontacts.core.protoncontacts.DecryptedContactJson
import io.pcontacts.core.protoncontacts.DecryptedEmail
import io.pcontacts.core.protoncontacts.DecryptedPhoto
import io.pcontacts.core.protoncontacts.PhotoHash
import io.pcontacts.core.storage.InMemoryMergeBaseStore
import io.pcontacts.core.storage.db.dao.ContactMapDao
import io.pcontacts.core.storage.db.dao.OutboxDao
import io.pcontacts.core.storage.db.entity.ContactMapEntity
import io.pcontacts.core.storage.db.entity.OutboxEntity
import io.pcontacts.core.sync.contacts.merge.MergeBaseCodec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

// Covers the write engine's many outbox paths (create/update/delete,
// merge, quarantine, backoff) in one place; large by subject, not by drift.
@Suppress("LargeClass")
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

    /** The user changed something on the phone, so the push has a change to carry. */
    private fun Map<String, DecryptedContact>.locallyEdited() =
        mapValues { it.value.copy(notes = listOf("local edit")) }

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

        val bases = InMemoryMergeBaseStore()
        val engine = newEngine(
            api,
            outbox,
            contactMap,
            contacts.locallyEdited(),
            serverContacts = contacts,
            bases = contacts,
            mergeBases = bases
        )
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
        assertNull(mapping.lastKnownServerPayloadHash)
        assertNotNull("the pushed payload becomes the merge base", bases.load("ct-1"))
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
        val contacts = mutableMapOf("local-1" to sampleContact("local-1"))

        contactMap.upsert(sampleMapping("local-1", rawId = 200L))
        outbox.insert(OutboxEntity(
            protonContactId = "local-1",
            opType = OutboxEntity.OpType.CREATE,
            payloadHash = "hash-new",
            createdAt = 1_000_000L
        ))

        val engine = newEngine(
            api,
            outbox,
            contactMap,
            contacts.locallyEdited(),
            serverContacts = contacts,
            bases = contacts
        )
        val report = engine.push()

        assertEquals(1, report.pushed)
        assertEquals(1, report.created)
        assertTrue(api.lastCreateRequest != null)
        assertTrue(outbox.entries.isEmpty())
        assertNull(contactMap.findByProtonId("local-1"))
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
            api,
            outbox,
            contactMap,
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
            api,
            outbox,
            contactMap,
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

        val engine = newEngine(
            api,
            outbox,
            contactMap,
            contacts.locallyEdited(),
            serverContacts = contacts,
            bases = contacts,
            clock = { 2_000_000_000L }
        )
        val report = engine.push()

        assertEquals(1, report.failed)
        assertEquals(0, report.quarantined)
        val entry = outbox.entries.values.single()
        assertEquals(1, entry.attempts)
        assertTrue(entry.nextAttemptAt > 2_000_000_000L)
    }

    @Test fun push_quarantine_reason_carries_protons_code_from_the_error_body() = runTest {
        val api = WriteFakeApi().apply {
            failWith = http(400, """{"Code":2002,"Error":"UID Field is missing"}""")
        }
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

        val engine = newEngine(
            api,
            outbox,
            contactMap,
            contacts.locallyEdited(),
            serverContacts = contacts,
            bases = contacts
        )
        val report = engine.push()

        assertEquals(1, report.quarantined)
        val entry = outbox.entries.values.single()
        assertTrue(entry.quarantined)
        // The server's text never reaches the reason; its code does.
        assertEquals("HTTP 400, Proton code 2002", entry.lastError)
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

        val engine = newEngine(
            api,
            outbox,
            contactMap,
            contacts.locallyEdited(),
            serverContacts = contacts,
            bases = contacts
        )
        val report = engine.push()

        assertEquals(0, report.failed)
        assertEquals(1, report.quarantined)
        val entry = outbox.entries.values.single()
        assertTrue(entry.quarantined)
        // Stable, R8-safe reason (not the minified exception class name).
        assertEquals("HTTP 400", entry.lastError)
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

    @Test fun push_create_writes_server_source_id_back_to_local_row() = runTest {
        // Prevents the create-orphan duplicate: the server id must be stamped
        // onto the local-<rawId> RawContact so the next pull matches it.
        val api = WriteFakeApi().apply {
            createResponse = CreateContactsResponse(
                code = 1000,
                responses = listOf(
                    CreateContactResponseItem(
                        index = 0,
                        response = CreateContactResponseBody(
                            code = 1000,
                            contact = ContactDto(id = "srv-99", uid = "uid-99")
                        )
                    )
                )
            )
        }
        val outbox = WriteFakeOutboxDao()
        val written = mutableListOf<Pair<Long, String>>()
        outbox.insert(
            OutboxEntity(
                protonContactId = "local-42",
                opType = OutboxEntity.OpType.CREATE,
                payloadHash = "h",
                createdAt = 1_000_000L
            )
        )
        val engine = newEngine(
            api = api,
            outbox = outbox,
            contacts = mapOf("local-42" to sampleContact("local-42")),
            writtenSourceIds = written
        )

        val report = engine.push(testAccount)

        assertEquals(1, report.created)
        assertEquals(listOf(42L to "srv-99"), written)
    }

    @Test fun push_create_without_account_skips_source_id_write() = runTest {
        val api = WriteFakeApi().apply {
            createResponse = CreateContactsResponse(
                code = 1000,
                responses = listOf(
                    CreateContactResponseItem(
                        index = 0,
                        response = CreateContactResponseBody(
                            code = 1000,
                            contact = ContactDto(id = "srv-1", uid = "uid-1")
                        )
                    )
                )
            )
        }
        val outbox = WriteFakeOutboxDao()
        val written = mutableListOf<Pair<Long, String>>()
        outbox.insert(
            OutboxEntity(
                protonContactId = "local-7",
                opType = OutboxEntity.OpType.CREATE,
                payloadHash = "h",
                createdAt = 1_000_000L
            )
        )
        val engine = newEngine(
            api = api,
            outbox = outbox,
            contacts = mapOf("local-7" to sampleContact("local-7")),
            writtenSourceIds = written
        )

        val report = engine.push()

        assertEquals(1, report.created)
        assertTrue(written.isEmpty())
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

        val engine = newEngine(
            api,
            outbox,
            contactMap,
            contacts.locallyEdited(),
            serverContacts = contacts,
            bases = contacts
        )
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

    @Test fun detectChanges_re_enqueues_update_when_prior_entry_quarantined() = runTest {
        val outbox = WriteFakeOutboxDao()
        val contactMap = WriteFakeContactMapDao()
        val row = sampleRow("ct-1", email = "new@proton.me")
        val hash = EmailSyncHash.compute(row)
        val rows = mutableMapOf(100L to row)

        contactMap.upsert(sampleMapping("ct-1", rawId = 100L))
        val id = outbox.insert(OutboxEntity(
            protonContactId = "ct-1",
            opType = OutboxEntity.OpType.UPDATE,
            payloadHash = hash,
            createdAt = 1_000_000L
        ))
        outbox.quarantine(id, "HttpException: 400")

        val engine = newEngine(
            outbox = outbox,
            contactMap = contactMap,
            dirtyContacts = listOf(DirtyContact(100L, "ct-1", isDirty = true, isDeleted = false)),
            contactRows = rows
        )
        val count = engine.detectChanges(testAccount)

        assertEquals(1, count)
        val nonQuarantined = outbox.entries.values.filter { !it.quarantined }
        assertEquals(1, nonQuarantined.size)
        assertEquals(hash, nonQuarantined.single().payloadHash)
    }

    @Test fun detectChanges_re_enqueues_delete_when_prior_entry_quarantined() = runTest {
        val outbox = WriteFakeOutboxDao()
        val contactMap = WriteFakeContactMapDao()
        contactMap.upsert(sampleMapping("ct-1", rawId = 100L))

        val id = outbox.insert(OutboxEntity(
            protonContactId = "ct-1",
            opType = OutboxEntity.OpType.DELETE,
            payloadHash = "",
            createdAt = 1_000_000L
        ))
        outbox.quarantine(id, "HttpException: 400")

        val engine = newEngine(
            outbox = outbox,
            contactMap = contactMap,
            dirtyContacts = listOf(DirtyContact(100L, "ct-1", isDirty = false, isDeleted = true))
        )
        val count = engine.detectChanges(testAccount)

        assertEquals(1, count)
        val nonQuarantined = outbox.entries.values.filter { !it.quarantined }
        assertEquals(1, nonQuarantined.size)
        assertEquals(OutboxEntity.OpType.DELETE, nonQuarantined.single().opType)
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

        contactMap.upsert(sampleMapping("ct-1", rawId = 100L))
        outbox.insert(OutboxEntity(
            protonContactId = "ct-1",
            opType = OutboxEntity.OpType.UPDATE,
            payloadHash = "hash-v2",
            createdAt = 1_000_000L
        ))

        val engine = newEngine(
            api,
            outbox,
            contactMap,
            contacts = mapOf("ct-1" to localContact),
            serverContacts = mapOf("ct-1" to serverContact),
            bases = mapOf("ct-1" to sampleContact("ct-1"))
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

        contactMap.upsert(sampleMapping("ct-1", rawId = 100L))
        outbox.insert(OutboxEntity(
            protonContactId = "ct-1",
            opType = OutboxEntity.OpType.UPDATE,
            payloadHash = "hash-v2",
            createdAt = 1_000_000L
        ))

        val engine = newEngine(
            api,
            outbox,
            contactMap,
            contacts = mapOf("ct-1" to localContact),
            serverContacts = mapOf("ct-1" to serverContact),
            bases = mapOf("ct-1" to baseContact)
        )
        val report = engine.push()

        assertEquals(0, report.pushed)
        assertEquals(1, report.conflicted)
        assertNull("nothing is pushed on a conflict", api.lastUpdateId)
        val mapping = contactMap.findByProtonId("ct-1")!!
        assertEquals(ContactMapEntity.Status.CONFLICT, mapping.syncStatus)
        assertTrue(mapping.lastError!!.contains("fullName"))
        assertNull("the outbox row is consumed; the conflict lives on the mapping", outbox.findLive("ct-1"))
    }

    @Test fun push_update_without_base_marks_no_merge_base_conflict_and_never_calls_PUT() = runTest {
        val api = WriteFakeApi()
        val outbox = WriteFakeOutboxDao()
        val contactMap = WriteFakeContactMapDao()
        val localContact = sampleContact("ct-1").copy(fullName = "Alice Updated")
        contactMap.upsert(sampleMapping("ct-1", rawId = 100L))
        outbox.enqueue("ct-1", OutboxEntity.OpType.UPDATE, "hash-v2", 1_000_000L)

        val engine = newEngine(
            api,
            outbox,
            contactMap,
            contacts = mapOf("ct-1" to localContact),
            serverContacts = mapOf("ct-1" to sampleContact("ct-1").copy(fullName = "Alice Server"))
        )
        val report = engine.push()

        assertEquals(0, report.pushed)
        assertEquals(1, report.conflicted)
        assertNull(api.lastUpdateId)
        val mapping = contactMap.findByProtonId("ct-1")!!
        assertEquals(ContactMapEntity.Status.CONFLICT, mapping.syncStatus)
        assertEquals("conflict: no merge base", mapping.lastError)
        assertNull(outbox.findLive("ct-1"))
    }

    @Test fun push_update_with_base_server_only_deletion_auto_merges() = runTest {
        val api = WriteFakeApi()
        val outbox = WriteFakeOutboxDao()
        val contactMap = WriteFakeContactMapDao()
        val twoEmails = sampleContact("ct-1").copy(
            emails = listOf(DecryptedEmail("alice@proton.me", isPrimary = true), DecryptedEmail("old@proton.me"))
        )
        contactMap.upsert(sampleMapping("ct-1", rawId = 100L))
        outbox.enqueue("ct-1", OutboxEntity.OpType.UPDATE, "hash-v2", 1_000_000L)
        val bases = InMemoryMergeBaseStore()

        val engine = newEngine(
            api,
            outbox,
            contactMap,
            contacts = mapOf("ct-1" to twoEmails),
            serverContacts = mapOf("ct-1" to sampleContact("ct-1")), // the server dropped old@
            bases = mapOf("ct-1" to twoEmails),
            mergeBases = bases
        )
        val report = engine.push()

        assertEquals(1, report.pushed)
        assertEquals(0, report.conflicted)
        // The merge accepts the deletion; the server already has that state, so no PUT is needed.
        assertNull(api.lastUpdateRequest)
        val base = DecryptedContactJson.decode(bases.load("ct-1")!!)!!
        assertEquals(listOf("alice@proton.me"), base.emails.map { it.address })
    }

    @Test fun push_update_with_base_local_only_deletion_auto_merges() = runTest {
        val api = WriteFakeApi()
        val outbox = WriteFakeOutboxDao()
        val contactMap = WriteFakeContactMapDao()
        val twoEmails = sampleContact("ct-1").copy(
            emails = listOf(DecryptedEmail("alice@proton.me", isPrimary = true), DecryptedEmail("old@proton.me"))
        )
        contactMap.upsert(sampleMapping("ct-1", rawId = 100L))
        outbox.enqueue("ct-1", OutboxEntity.OpType.UPDATE, "hash-v2", 1_000_000L)

        val engine = newEngine(
            api,
            outbox,
            contactMap,
            contacts = mapOf("ct-1" to sampleContact("ct-1")), // the user removed old@ locally
            serverContacts = mapOf("ct-1" to twoEmails),
            bases = mapOf("ct-1" to twoEmails)
        )
        val report = engine.push()

        assertEquals(1, report.pushed)
        assertEquals(0, report.conflicted)
        assertFalse(
            "the local deletion is preserved, not resurrected",
            api.lastUpdateRequest!!.cards.any { it.data.contains("old@proton.me") }
        )
    }

    @Test fun push_update_with_base_one_sided_scalar_change_auto_merges() = runTest {
        val api = WriteFakeApi()
        val outbox = WriteFakeOutboxDao()
        val contactMap = WriteFakeContactMapDao()
        contactMap.upsert(sampleMapping("ct-1", rawId = 100L))
        outbox.enqueue("ct-1", OutboxEntity.OpType.UPDATE, "hash-v2", 1_000_000L)
        val bases = InMemoryMergeBaseStore()

        val engine = newEngine(
            api,
            outbox,
            contactMap,
            contacts = mapOf("ct-1" to sampleContact("ct-1").copy(fullName = "Alice Local")),
            serverContacts = mapOf("ct-1" to sampleContact("ct-1")),
            bases = mapOf("ct-1" to sampleContact("ct-1")),
            mergeBases = bases
        )
        val report = engine.push()

        assertEquals(1, report.pushed)
        assertEquals(0, report.conflicted)
        assertTrue(api.lastUpdateRequest!!.cards.any { it.data.contains("Alice Local") })
        assertEquals("the pushed payload is the new base", "Alice Local", MergeBaseCodec.load(bases, "ct-1")!!.fullName)
    }

    @Test fun push_update_server_fetch_failure_backs_off_instead_of_local_wins() = runTest {
        val api = WriteFakeApi()
        val outbox = WriteFakeOutboxDao()
        val contactMap = WriteFakeContactMapDao()
        contactMap.upsert(sampleMapping("ct-1", rawId = 100L))
        outbox.enqueue("ct-1", OutboxEntity.OpType.UPDATE, "hash-v2", 1_000_000L)

        val engine = newEngine(
            api,
            outbox,
            contactMap,
            contacts = mapOf("ct-1" to sampleContact("ct-1")),
            bases = mapOf("ct-1" to sampleContact("ct-1")),
            fetchServerContact = { throw http(503) }
        )
        val report = engine.push()

        assertEquals(1, report.failed)
        assertNull(api.lastUpdateId)
        val live = outbox.findLive("ct-1")!!
        assertEquals(1, live.attempts)
        assertEquals("HTTP 503", live.lastError)
    }

    @Test fun push_force_update_skips_the_merge_and_pushes_local() = runTest {
        val api = WriteFakeApi()
        val outbox = WriteFakeOutboxDao()
        val contactMap = WriteFakeContactMapDao()
        contactMap.upsert(sampleMapping("ct-1", rawId = 100L))
        outbox.enqueue("ct-1", OutboxEntity.OpType.FORCE_UPDATE, "", 1_000_000L)

        val engine = newEngine(
            api,
            outbox,
            contactMap,
            contacts = mapOf("ct-1" to sampleContact("ct-1").copy(notes = listOf("phone"))),
            serverContacts = mapOf("ct-1" to sampleContact("ct-1"))
        )
        val report = engine.push()

        assertEquals(1, report.pushed)
        assertEquals(0, report.conflicted)
        assertEquals("ct-1", api.lastUpdateId)
        assertNull(outbox.findLive("ct-1"))
    }

    @Test fun push_update_re_enqueues_when_the_contact_changed_during_the_push() = runTest {
        val api = WriteFakeApi()
        val outbox = WriteFakeOutboxDao()
        val contactMap = WriteFakeContactMapDao()
        val rows = mutableMapOf(100L to sampleRow("ct-1"))
        contactMap.upsert(sampleMapping("ct-1", rawId = 100L))
        outbox.enqueue("ct-1", OutboxEntity.OpType.UPDATE, EmailSyncHash.compute(rows[100L]!!), 1_000_000L)
        api.onUpdate = { rows[100L] = sampleRow("ct-1", email = "changed@proton.me") }

        val engine = newEngine(
            api,
            outbox,
            contactMap,
            contactRows = rows,
            serverContacts = mapOf("ct-1" to sampleContact("ct-1").copy(notes = listOf("base"))),
            bases = mapOf("ct-1" to sampleContact("ct-1").copy(notes = listOf("base")))
        )
        val report = engine.push()

        assertEquals(1, report.pushed)
        val live = outbox.findLive("ct-1")!!
        assertEquals(OutboxEntity.OpType.UPDATE, live.opType)
        assertEquals(EmailSyncHash.compute(rows[100L]!!), live.payloadHash)
    }

    @Test fun push_create_saves_the_merge_base_under_the_server_id() = runTest {
        val api = WriteFakeApi().apply {
            createResponse = CreateContactsResponse(
                code = 1000,
                responses = listOf(
                    CreateContactResponseItem(
                        index = 0,
                        response = CreateContactResponseBody(
                            code = 1000,
                            contact = ContactDto(id = "server-42", uid = "server-uid-42", cards = emptyList())
                        )
                    )
                )
            )
        }
        val outbox = WriteFakeOutboxDao()
        val contactMap = WriteFakeContactMapDao()
        contactMap.upsert(sampleMapping("local-42", rawId = 42L))
        outbox.enqueue("local-42", OutboxEntity.OpType.CREATE, "hash-v1", 1_000_000L)
        val bases = InMemoryMergeBaseStore()

        val engine = newEngine(
            api,
            outbox,
            contactMap,
            contacts = mapOf("local-42" to sampleContact("local-42")),
            mergeBases = bases
        )
        val report = engine.push()

        assertEquals(1, report.created)
        assertNotNull(bases.load("server-42"))
        assertNull(contactMap.findByProtonId("local-42"))
        assertNotNull(contactMap.findByProtonId("server-42"))
        assertTrue(outbox.entries.isEmpty())
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

    @Test fun detectChanges_clears_dirty_flag_when_hash_unchanged() = runTest {
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

    @Test fun detectChanges_keeps_dirty_flag_when_contact_row_unreadable() = runTest {
        val outbox = WriteFakeOutboxDao()
        val contactMap = WriteFakeContactMapDao()
        val cleared = mutableListOf<Long>()

        contactMap.upsert(sampleMapping("ct-1", rawId = 100L))

        val engine = newEngine(
            outbox = outbox,
            contactMap = contactMap,
            dirtyContacts = listOf(DirtyContact(100L, "ct-1", isDirty = true, isDeleted = false)),
            contactRows = emptyMap(),
            clearedFlags = cleared
        )
        val count = engine.detectChanges(testAccount)

        assertEquals(0, count)
        assertFalse(cleared.contains(100L))
    }

    // --- helpers ---

    // --- one live row per contact (ADR-0017 §5 amendment) ---

    @Test fun detectChanges_two_different_hash_updates_yield_one_row_with_the_newest_hash() = runTest {
        val outbox = WriteFakeOutboxDao()
        val contactMap = WriteFakeContactMapDao()
        val rows = mutableMapOf(100L to sampleRow("ct-1", email = "v1@proton.me"))
        contactMap.upsert(sampleMapping("ct-1", rawId = 100L))
        val dirty = listOf(DirtyContact(100L, "ct-1", isDirty = true, isDeleted = false))
        val engine = newEngine(outbox = outbox, contactMap = contactMap, dirtyContacts = dirty, contactRows = rows)

        engine.detectChanges(testAccount)
        rows[100L] = sampleRow("ct-1", email = "v2@proton.me")
        engine.detectChanges(testAccount)

        assertEquals(1, outbox.entries.size)
        assertEquals(EmailSyncHash.compute(rows[100L]!!), outbox.findLive("ct-1")!!.payloadHash)
    }

    @Test fun detectChanges_update_after_a_pending_create_keeps_the_create() = runTest {
        val outbox = WriteFakeOutboxDao()
        val rows = mutableMapOf(500L to sampleRow("local-500", email = "v1@proton.me"))
        val dirty = listOf(DirtyContact(500L, null, isDirty = true, isDeleted = false))
        val engine = newEngine(outbox = outbox, dirtyContacts = dirty, contactRows = rows)

        engine.detectChanges(testAccount)
        rows[500L] = sampleRow("local-500", email = "v2@proton.me")
        engine.detectChanges(testAccount)

        val live = outbox.findLive("local-500")!!
        assertEquals(1, outbox.entries.size)
        assertEquals(OutboxEntity.OpType.CREATE, live.opType)
        assertEquals(EmailSyncHash.compute(rows[500L]!!), live.payloadHash)
    }

    @Test fun detectChanges_delete_of_an_unpushed_create_drops_the_row_and_never_posts() = runTest {
        val api = WriteFakeApi()
        val outbox = WriteFakeOutboxDao()
        val rows = mutableMapOf(500L to sampleRow("local-500"))
        val engine = newEngine(
            api = api,
            outbox = outbox,
            dirtyContacts = listOf(DirtyContact(500L, null, isDirty = true, isDeleted = false)),
            contactRows = rows
        )
        engine.detectChanges(testAccount)
        assertEquals(1, outbox.entries.size)

        val deleting = newEngine(
            api = api,
            outbox = outbox,
            dirtyContacts = listOf(DirtyContact(500L, null, isDirty = false, isDeleted = true)),
            contactRows = rows
        )
        assertEquals(0, deleting.detectChanges(testAccount))
        deleting.push()

        assertTrue(outbox.entries.isEmpty())
        assertNull(api.lastCreateRequest)
    }

    @Test fun detectChanges_delete_supersedes_a_pending_update() = runTest {
        val outbox = WriteFakeOutboxDao()
        val contactMap = WriteFakeContactMapDao()
        val rows = mutableMapOf(100L to sampleRow("ct-1", email = "v2@proton.me"))
        contactMap.upsert(sampleMapping("ct-1", rawId = 100L))
        newEngine(
            outbox = outbox,
            contactMap = contactMap,
            dirtyContacts = listOf(DirtyContact(100L, "ct-1", isDirty = true, isDeleted = false)),
            contactRows = rows
        ).detectChanges(testAccount)

        newEngine(
            outbox = outbox,
            contactMap = contactMap,
            dirtyContacts = listOf(DirtyContact(100L, "ct-1", isDirty = false, isDeleted = true)),
            contactRows = rows,
            clock = { 7_000L }
        ).detectChanges(testAccount)

        val live = outbox.findLive("ct-1")!!
        assertEquals(1, outbox.entries.size)
        assertEquals(OutboxEntity.OpType.DELETE, live.opType)
        assertEquals("the grace period restarts at the delete", 7_000L, live.createdAt)
    }

    @Test fun push_two_legacy_create_rows_for_the_same_contact_post_once() = runTest {
        val api = WriteFakeApi().apply {
            createResponse = CreateContactsResponse(
                code = 1000,
                responses = listOf(
                    CreateContactResponseItem(
                        index = 0,
                        response = CreateContactResponseBody(
                            code = 1000,
                            contact = ContactDto(id = "server-42", uid = "server-uid-42", cards = emptyList())
                        )
                    )
                )
            )
        }
        val gate = CompletableDeferred<Unit>()
        var creates = 0
        api.onCreate = {
            creates++
            gate.await()
        }
        val outbox = WriteFakeOutboxDao()
        val contactMap = WriteFakeContactMapDao()
        contactMap.upsert(sampleMapping("local-42", rawId = 42L))
        // Two live rows as a pre-v3 database could hold them.
        outbox.insert(legacyCreate("local-42", "h1", createdAt = 1L))
        outbox.insert(legacyCreate("local-42", "h2", createdAt = 2L))
        val engine = newEngine(api, outbox, contactMap, contacts = mapOf("local-42" to sampleContact("local-42")))

        val job = launch { engine.push() }
        advanceUntilIdle()
        assertEquals("the second row must not POST while the first is in flight", 1, creates)
        gate.complete(Unit)
        job.join()

        assertEquals(1, creates)
        assertTrue(outbox.entries.isEmpty())
    }

    @Test fun push_different_contacts_still_run_concurrently() = runTest {
        val api = WriteFakeApi()
        val gate = CompletableDeferred<Unit>()
        var inFlight = 0
        var peak = 0
        api.onUpdate = {
            inFlight++
            peak = maxOf(peak, inFlight)
            gate.await()
            inFlight--
        }
        val outbox = WriteFakeOutboxDao()
        val contactMap = WriteFakeContactMapDao()
        val contacts = mapOf("ct-1" to sampleContact("ct-1"), "ct-2" to sampleContact("ct-2"))
        contactMap.upsert(sampleMapping("ct-1", rawId = 100L))
        contactMap.upsert(sampleMapping("ct-2", rawId = 200L))
        outbox.enqueue("ct-1", OutboxEntity.OpType.UPDATE, "h1", 1L)
        outbox.enqueue("ct-2", OutboxEntity.OpType.UPDATE, "h2", 2L)
        val engine = newEngine(
            api,
            outbox,
            contactMap,
            contacts.locallyEdited(),
            serverContacts = contacts,
            bases = contacts
        )

        val job = launch { engine.push() }
        advanceUntilIdle()
        gate.complete(Unit)
        job.join()

        assertEquals(2, peak)
        assertTrue(outbox.entries.isEmpty())
    }

    @Test fun push_counts_the_changes_it_sends_and_leaves_out_deletes_still_in_grace() = runTest {
        val outbox = WriteFakeOutboxDao()
        val contactMap = WriteFakeContactMapDao()
        val contacts = mapOf("ct-1" to sampleContact("ct-1"), "ct-2" to sampleContact("ct-2"))
        contactMap.upsert(sampleMapping("ct-1", rawId = 100L))
        contactMap.upsert(sampleMapping("ct-2", rawId = 200L))
        contactMap.upsert(sampleMapping("ct-3", rawId = 300L))
        outbox.enqueue("ct-1", OutboxEntity.OpType.UPDATE, "h1", 1L)
        outbox.enqueue("ct-2", OutboxEntity.OpType.UPDATE, "h2", 2L)
        outbox.enqueue("ct-3", OutboxEntity.OpType.DELETE, "", 2_000_000_000L)
        val progress = mutableListOf<Triple<SyncPhase, Int, Int>>()
        val engine = newEngine(
            outbox = outbox,
            contactMap = contactMap,
            contacts = contacts.locallyEdited(),
            serverContacts = contacts,
            bases = contacts,
            onProgress = { phase, done, total -> progress += Triple(phase, done, total) }
        )

        val report = engine.push()

        assertEquals(1, report.skippedGrace)
        assertEquals(
            listOf(
                Triple(SyncPhase.SENDING, 0, 2),
                Triple(SyncPhase.SENDING, 1, 2),
                Triple(SyncPhase.SENDING, 2, 2)
            ),
            progress
        )
    }

    // ---- Proton item codes and the lost-response create (M4, A12) ----

    /** A create queued for RawContact 200: the local id carries the raw id, as the write engine mints it. */
    private suspend fun queuedCreate(outbox: WriteFakeOutboxDao, contactMap: WriteFakeContactMapDao) {
        contactMap.upsert(sampleMapping("local-200", rawId = 200L))
        outbox.insert(legacyCreate("local-200", "hash-new", 1_000_000L))
    }

    @Test fun push_create_whose_response_was_lost_recovers_the_contact_by_uid() = runTest {
        val uid = ContactSerializer.fallbackUid("local-200")
        val api = WriteFakeApi().apply {
            createFailsAfterCommit = IOException("connection reset")
            listedContacts = listOf(ContactMetadataDto(id = "srv-9", uid = uid))
        }
        val outbox = WriteFakeOutboxDao()
        val contactMap = WriteFakeContactMapDao()
        queuedCreate(outbox, contactMap)
        val written = mutableListOf<Pair<Long, String>>()
        val engine = newEngine(
            api,
            outbox,
            contactMap,
            contacts = mapOf("local-200" to sampleContact("local-200")),
            writtenSourceIds = written
        )

        val report = engine.push(testAccount)

        assertEquals(1, report.created)
        assertEquals(0, report.failed)
        assertNull(contactMap.findByProtonId("local-200"))
        assertEquals(uid, contactMap.findByProtonId("srv-9")!!.protonUid)
        assertEquals(listOf(200L to "srv-9"), written)
        assertTrue(outbox.entries.isEmpty())
    }

    @Test fun push_create_refused_by_item_code_is_quarantined_with_the_code_unless_the_uid_exists() = runTest {
        val refused = CreateContactsResponse(
            code = 1001,
            responses = listOf(CreateContactResponseItem(0, CreateContactResponseBody(code = 2001)))
        )
        val api = WriteFakeApi().apply { createResponse = refused }
        val outbox = WriteFakeOutboxDao()
        val contactMap = WriteFakeContactMapDao()
        queuedCreate(outbox, contactMap)
        val engine = newEngine(
            api,
            outbox,
            contactMap,
            contacts = mapOf("local-200" to sampleContact("local-200"))
        )

        val report = engine.push(testAccount)

        assertEquals(1, report.quarantined)
        val row = outbox.entries.values.single()
        assertTrue(row.quarantined)
        assertEquals("Proton code 2001", row.lastError)

        // The same refusal with the contact already present under our UID is a success.
        api.listedContacts = listOf(ContactMetadataDto(id = "srv-9", uid = ContactSerializer.fallbackUid("local-2")))
        contactMap.upsert(sampleMapping("local-2", rawId = 201L))
        outbox.insert(legacyCreate("local-2", "hash-new", 1_000_000L))
        val engine2 = newEngine(
            api,
            outbox,
            contactMap,
            contacts = mapOf("local-2" to sampleContact("local-2"))
        )
        assertEquals(1, engine2.push(testAccount).created)
        assertNotNull(contactMap.findByProtonId("srv-9"))
    }

    @Test fun push_create_whose_response_carries_no_item_stays_queued_and_is_never_reported_created() = runTest {
        val api = WriteFakeApi().apply { createResponse = CreateContactsResponse(code = 1001, responses = emptyList()) }
        val outbox = WriteFakeOutboxDao()
        val contactMap = WriteFakeContactMapDao()
        queuedCreate(outbox, contactMap)
        val written = mutableListOf<Pair<Long, String>>()
        val engine = newEngine(
            api,
            outbox,
            contactMap,
            contacts = mapOf("local-200" to sampleContact("local-200")),
            writtenSourceIds = written
        )

        val report = engine.push(testAccount)

        assertEquals(0, report.created)
        assertEquals(1, report.failed)
        val row = outbox.entries.values.single()
        assertFalse(row.quarantined)
        assertEquals(1, row.attempts)
        assertNotNull(contactMap.findByProtonId("local-200"))
        assertTrue(written.isEmpty())
    }

    @Test fun push_create_accepted_without_a_contact_is_settled_by_the_uid_lookup_only() = runTest {
        val uid = ContactSerializer.fallbackUid("local-200")
        val api = WriteFakeApi().apply {
            createResponse = CreateContactsResponse(
                code = 1001,
                responses = listOf(CreateContactResponseItem(0, CreateContactResponseBody(code = 1000)))
            )
        }
        val outbox = WriteFakeOutboxDao()
        val contactMap = WriteFakeContactMapDao()
        queuedCreate(outbox, contactMap)
        val written = mutableListOf<Pair<Long, String>>()
        val engine = newEngine(
            api,
            outbox,
            contactMap,
            contacts = mapOf("local-200" to sampleContact("local-200")),
            writtenSourceIds = written
        )

        // Nothing under our UID: the CREATE stays queued.
        assertEquals(1, engine.push(testAccount).failed)
        assertNotNull(contactMap.findByProtonId("local-200"))
        assertFalse(outbox.entries.values.single().quarantined)
        assertTrue(written.isEmpty())

        // The contact is there under our UID: recovered like a lost response.
        api.listedContacts = listOf(ContactMetadataDto(id = "srv-9", uid = uid))
        outbox.entries.values.single().let { outbox.entries[it.id] = it.copy(nextAttemptAt = 0L) }
        val report = engine.push(testAccount)
        assertEquals(1, report.created)
        assertNull(contactMap.findByProtonId("local-200"))
        assertEquals(uid, contactMap.findByProtonId("srv-9")!!.protonUid)
        assertEquals(listOf(200L to "srv-9"), written)
        assertTrue(outbox.entries.isEmpty())
    }

    @Test fun push_update_of_a_contact_deleted_on_proton_becomes_a_server_deleted_conflict() = runTest {
        val outbox = WriteFakeOutboxDao()
        val contactMap = WriteFakeContactMapDao()
        contactMap.upsert(sampleMapping("ct-1", rawId = 100L))
        outbox.insert(
            OutboxEntity(
                protonContactId = "ct-1",
                opType = OutboxEntity.OpType.UPDATE,
                payloadHash = "h",
                createdAt = 0L
            )
        )
        val engine = newEngine(
            outbox = outbox,
            contactMap = contactMap,
            contacts = mapOf("ct-1" to sampleContact("ct-1")),
            fetchServerContact = { throw HttpException(Response.error<Any>(404, "".toResponseBody())) }
        )

        val report = engine.push(testAccount)

        assertEquals(1, report.conflicted)
        assertEquals(0, report.quarantined)
        val mapping = contactMap.findByProtonId("ct-1")!!
        assertEquals(ContactMapEntity.Status.CONFLICT, mapping.syncStatus)
        assertEquals(SERVER_DELETED_CONFLICT, mapping.lastError)
        assertTrue(outbox.entries.isEmpty())
    }

    @Test fun push_delete_without_an_item_acknowledgement_stays_queued() = runTest {
        val api = WriteFakeApi().apply { deleteAckMissing = true }
        val outbox = WriteFakeOutboxDao()
        val contactMap = WriteFakeContactMapDao()
        contactMap.upsert(sampleMapping("ct-1", rawId = 100L))
        outbox.insert(
            OutboxEntity(
                protonContactId = "ct-1",
                opType = OutboxEntity.OpType.DELETE,
                payloadHash = "",
                createdAt = 0L
            )
        )
        val engine = newEngine(api, outbox, contactMap)

        val report = engine.push(testAccount)

        assertEquals(0, report.deleted)
        assertEquals(1, report.failed)
        assertFalse(outbox.entries.values.single().quarantined)
        assertNotNull(contactMap.findByProtonId("ct-1"))
    }

    @Test fun push_delete_refused_by_item_code_is_quarantined_with_the_code() = runTest {
        val api = WriteFakeApi().apply { deleteItemCode = 2501 }
        val outbox = WriteFakeOutboxDao()
        val contactMap = WriteFakeContactMapDao()
        contactMap.upsert(sampleMapping("ct-1", rawId = 100L))
        outbox.insert(
            OutboxEntity(
                protonContactId = "ct-1",
                opType = OutboxEntity.OpType.DELETE,
                payloadHash = "",
                createdAt = 1L
            )
        )
        val engine = newEngine(
            api,
            outbox,
            contactMap,
            clock = { 1L + ContactWriteEngine.GRACE_PERIOD_MS + 1 }
        )

        val report = engine.push()

        assertEquals(1, report.quarantined)
        assertEquals("Proton code 2501", outbox.entries.values.single().lastError)
        assertNotNull("the mapping stays until Proton accepts the delete", contactMap.findByProtonId("ct-1"))
    }

    // ---- ADR-0017 §2 Choice 2C: an update patches the server's cards ----

    /** Decrypts nothing: the "server" cards are plaintext vCards tagged with their real types. */
    private val carrierProcessor = ContactProcessor(
        ContactDecrypter(
            cryptoOp = { request ->
                when (request) {
                    is CardCryptoRequest.VerifyOnly -> CardCryptoOutcome(request.data, verified = true)
                    is CardCryptoRequest.DecryptAndVerify -> CardCryptoOutcome(request.armored, verified = true)
                    is CardCryptoRequest.DecryptOnly -> CardCryptoOutcome(request.armored, verified = false)
                }
            }
        )
    )

    private fun richServerContact(
        id: String = "ct-1",
        note: String = "old",
        photoBase64: String? = null
    ): DecryptedContact {
        val signed = "BEGIN:VCARD\nVERSION:4.0\nFN:Alice\nUID:uid-1\nitem1.EMAIL;PREF=1:alice@proton.me\n" +
            "item1.KEY:data:application/pgp-keys;base64,AAAA\nEND:VCARD"
        val photoLine = photoBase64?.let { "PHOTO;MEDIATYPE=image/jpeg:data:image/jpeg;base64,$it\n" }.orEmpty()
        val encrypted = "BEGIN:VCARD\nVERSION:4.0\nTEL;TYPE=cell:+15550001\nBDAY:19800101\nCATEGORIES:Friends\n" +
            "NOTE:$note\n${photoLine}END:VCARD"
        return carrierProcessor.process(
            ContactDto(
                id = id,
                uid = "uid-1",
                cards = listOf(
                    ContactCardDto(type = CardType.SIGNED.wireValue, data = signed, signature = "s"),
                    ContactCardDto(type = CardType.ENCRYPTED_AND_SIGNED.wireValue, data = encrypted, signature = "s")
                )
            )
        )
    }

    private fun UpdateContactRequest.card(type: CardType) = cards.single { it.type == type.wireValue }.data

    @Test fun push_update_patches_the_server_cards_and_keeps_unowned_properties() = runTest {
        val api = WriteFakeApi()
        val outbox = WriteFakeOutboxDao()
        val contactMap = WriteFakeContactMapDao()
        val server = richServerContact(note = "old")
        val local = MergeBaseCodec.canonical(server)!!.copy(notes = listOf("new"))
        contactMap.upsert(sampleMapping("ct-1", rawId = 100L))
        outbox.enqueue("ct-1", OutboxEntity.OpType.UPDATE, "h", 1_000_000L)
        val engine = newEngine(
            api,
            outbox,
            contactMap,
            contacts = mapOf("ct-1" to local),
            serverContacts = mapOf("ct-1" to server),
            bases = mapOf("ct-1" to server)
        )

        val report = engine.push()

        assertEquals(1, report.updated)
        val request = api.lastUpdateRequest!!
        assertEquals(2, request.cards.size)
        val signed = request.card(CardType.SIGNED)
        assertTrue(signed.contains("UID:uid-1"))
        assertTrue(signed.contains("item1.EMAIL;PREF=1:alice@proton.me"))
        assertTrue(signed.contains("item1.KEY:"))
        val encrypted = request.card(CardType.ENCRYPTED_AND_SIGNED)
        assertTrue(encrypted.contains("BDAY:19800101"))
        assertTrue(encrypted.contains("CATEGORIES:Friends"))
        assertTrue(encrypted.contains("TEL;TYPE=cell:+15550001"))
        assertTrue(encrypted.contains("NOTE:new"))
        assertFalse(encrypted.contains("NOTE:old"))
    }

    @Test fun push_update_with_nothing_left_to_change_completes_without_a_PUT() = runTest {
        val api = WriteFakeApi()
        val outbox = WriteFakeOutboxDao()
        val contactMap = WriteFakeContactMapDao()
        val server = richServerContact()
        contactMap.upsert(sampleMapping("ct-1", rawId = 100L))
        outbox.enqueue("ct-1", OutboxEntity.OpType.UPDATE, "h", 1_000_000L)
        val bases = InMemoryMergeBaseStore()
        val engine = newEngine(
            api,
            outbox,
            contactMap,
            contacts = mapOf("ct-1" to MergeBaseCodec.canonical(server)!!),
            serverContacts = mapOf("ct-1" to server),
            bases = mapOf("ct-1" to server),
            mergeBases = bases
        )

        val report = engine.push()

        assertEquals(1, report.updated)
        assertNull(api.lastUpdateRequest)
        assertTrue(outbox.entries.isEmpty())
        assertEquals(ContactMapEntity.Status.CLEAN, contactMap.findByProtonId("ct-1")!!.syncStatus)
        assertNotNull(bases.load("ct-1"))
    }

    @Test fun push_force_update_still_patches_the_server_cards() = runTest {
        val api = WriteFakeApi()
        val outbox = WriteFakeOutboxDao()
        val contactMap = WriteFakeContactMapDao()
        val server = richServerContact(note = "server")
        val local = MergeBaseCodec.canonical(server)!!.copy(notes = listOf("phone"))
        contactMap.upsert(sampleMapping("ct-1", rawId = 100L))
        outbox.enqueue("ct-1", OutboxEntity.OpType.FORCE_UPDATE, "", 1_000_000L)
        val engine = newEngine(
            api,
            outbox,
            contactMap,
            contacts = mapOf("ct-1" to local),
            serverContacts = mapOf("ct-1" to server)
        )

        engine.push()

        val encrypted = api.lastUpdateRequest!!.card(CardType.ENCRYPTED_AND_SIGNED)
        assertTrue(encrypted.contains("NOTE:phone"))
        assertTrue(encrypted.contains("BDAY:19800101"))
        assertTrue(encrypted.contains("CATEGORIES:Friends"))
    }

    @Test fun push_update_keeps_a_newer_server_photo_through_an_unrelated_local_edit() = runTest {
        val api = WriteFakeApi()
        val outbox = WriteFakeOutboxDao()
        val contactMap = WriteFakeContactMapDao()
        val photoA = byteArrayOf(1, 1, 1)
        val localA = byteArrayOf(1, 1, 2) // the provider's re-encoding of A
        val photoB = byteArrayOf(2, 2, 2)
        val b64 = java.util.Base64.getEncoder()
        val serverWithB = richServerContact(photoBase64 = b64.encodeToString(photoB))
        val base = richServerContact(photoBase64 = b64.encodeToString(photoA))
            .copy(localPhotoHash = PhotoHash.of(localA))
        val local = MergeBaseCodec.canonical(base)!!.copy(
            notes = listOf("edited"),
            photo = DecryptedPhoto(localA)
        )
        contactMap.upsert(sampleMapping("ct-1", rawId = 100L))
        outbox.enqueue("ct-1", OutboxEntity.OpType.UPDATE, "h", 1_000_000L)
        val engine = newEngine(
            api,
            outbox,
            contactMap,
            contacts = mapOf("ct-1" to local),
            serverContacts = mapOf("ct-1" to serverWithB),
            bases = mapOf("ct-1" to base)
        )

        val report = engine.push()

        assertEquals(0, report.conflicted)
        val encrypted = api.lastUpdateRequest!!.card(CardType.ENCRYPTED_AND_SIGNED)
        assertTrue(encrypted.contains(b64.encodeToString(photoB)))
        assertTrue(encrypted.contains("NOTE:edited"))
    }

    @Test fun push_cancelled_mid_flight_leaves_the_row_retryable_and_propagates() = runTest {
        val api = WriteFakeApi()
        val gate = CompletableDeferred<Unit>()
        api.onUpdate = { gate.await() }
        val outbox = WriteFakeOutboxDao()
        val contactMap = WriteFakeContactMapDao()
        val contacts = mapOf("ct-1" to sampleContact("ct-1"))
        contactMap.upsert(sampleMapping("ct-1", rawId = 100L))
        outbox.enqueue("ct-1", OutboxEntity.OpType.UPDATE, "h1", 1L)
        val engine = newEngine(
            api,
            outbox,
            contactMap,
            contacts.locallyEdited(),
            serverContacts = contacts,
            bases = contacts
        )

        val job = launch { engine.push() }
        advanceUntilIdle()
        job.cancel()
        job.join()

        assertTrue(job.isCancelled)
        val row = outbox.entries.values.single()
        assertFalse("a cancelled push is not a failure", row.quarantined)
        assertEquals(0, row.attempts)
    }

    // Test factory: all seams optional, so the parameter count is by design.
    @Suppress("LongParameterList")
    private suspend fun newEngine(
        api: WriteFakeApi = WriteFakeApi(),
        outbox: WriteFakeOutboxDao = WriteFakeOutboxDao(),
        contactMap: WriteFakeContactMapDao = WriteFakeContactMapDao(),
        contacts: Map<String, DecryptedContact> = emptyMap(),
        dirtyContacts: List<DirtyContact> = emptyList(),
        contactRows: Map<Long, ContactRow> = emptyMap(),
        clearedFlags: MutableList<Long>? = null,
        serverContacts: Map<String, DecryptedContact> = emptyMap(),
        writtenSourceIds: MutableList<Pair<Long, String>>? = null,
        clock: () -> Long = { 2_000_000_000L },
        bases: Map<String, DecryptedContact> = emptyMap(),
        mergeBases: InMemoryMergeBaseStore = InMemoryMergeBaseStore(),
        fetchServerContact: suspend (String) -> DecryptedContact? = { id -> serverContacts[id] },
        onProgress: (SyncPhase, Int, Int) -> Unit = { _, _, _ -> }
    ): ContactWriteEngine {
        bases.forEach { (id, contact) -> MergeBaseCodec.save(mergeBases, id, contact) }
        return ContactWriteEngine(
            contactsApi = api,
            serializer = serializer,
            outboxDao = outbox,
            contactMapDao = contactMap,
            mergeBases = mergeBases,
            readDirtyContacts = { dirtyContacts },
            // A row by raw id, or — for the push tests — the contact keyed by source id, projected to its row.
            readContactRow = { rawId, sourceId ->
                contactRows[rawId]
                    ?: contacts[sourceId]?.let { DecryptedContactToRow.convert(it)?.copy(sourceId = sourceId) }
            },
            clearDirtyFlag = { _, rawId -> clearedFlags?.add(rawId) },
            writeSourceId = { _, rawId, sourceId -> writtenSourceIds?.add(rawId to sourceId) },
            fetchServerContact = fetchServerContact,
            onProgress = onProgress,
            clock = clock
        )
    }

    private fun legacyCreate(contactId: String, hash: String, createdAt: Long) = OutboxEntity(
        protonContactId = contactId,
        opType = OutboxEntity.OpType.CREATE,
        payloadHash = hash,
        createdAt = createdAt
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

    private fun http(code: Int, body: String = ""): HttpException =
        HttpException(Response.error<Unit>(code, body.toResponseBody(null)))
}

// --- fakes ---

private class WriteFakeApi : ProtonContactsApi {
    var failWith: Exception? = null
    var lastUpdateId: String? = null
    var lastUpdateRequest: UpdateContactRequest? = null
    var lastCreateRequest: CreateContactsRequest? = null
    var lastDeleteRequest: BulkDeleteRequest? = null
    var createResponse = CreateContactsResponse(code = 1000)

    /** What `listContacts` returns — the UID lookup after a lost or refused create reads it. */
    var listedContacts: List<ContactMetadataDto> = emptyList()

    /** Thrown by `createContacts` after the request "reached" Proton (the response was lost). */
    var createFailsAfterCommit: Exception? = null

    /** The per-item Code `deleteContacts` answers with. */
    var deleteItemCode: Int = 1000

    /** When true, `deleteContacts` answers the 1001 envelope with no item at all. */
    var deleteAckMissing: Boolean = false

    /** Runs inside a successful PUT — the hook for "the contact changed during the push" and for gating. */
    var onUpdate: (suspend () -> Unit)? = null

    /** Runs inside a successful POST, before the response — the hook for gating concurrent creates. */
    var onCreate: (suspend () -> Unit)? = null

    override suspend fun listContactEmails(page: Int, pageSize: Int, emailFilter: String?, labelIdFilter: String?) =
        ContactEmailsPageResponse(code = 1000)
    override suspend fun getContact(id: String) =
        error("not used in write engine tests")
    override suspend fun listContacts(page: Int, pageSize: Int, labelIdFilter: String?) =
        ContactsPageResponse(code = 1000, contacts = listedContacts, total = listedContacts.size)

    override suspend fun createContacts(request: CreateContactsRequest): CreateContactsResponse {
        failWith?.let { throw it }
        lastCreateRequest = request
        onCreate?.invoke()
        createFailsAfterCommit?.let { throw it }
        return createResponse
    }

    override suspend fun updateContact(id: String, request: UpdateContactRequest): UpdateContactResponse {
        failWith?.let { throw it }
        lastUpdateId = id
        lastUpdateRequest = request
        onUpdate?.invoke()
        return UpdateContactResponse(code = 1000)
    }

    override suspend fun deleteContacts(request: BulkDeleteRequest): BulkDeleteResponse {
        failWith?.let { throw it }
        lastDeleteRequest = request
        return BulkDeleteResponse(
            code = 1001,
            responses = if (deleteAckMissing) {
                emptyList()
            } else {
                request.ids.map { DeleteResponseItem(it, DeleteResponseBody(code = deleteItemCode)) }
            }
        )
    }
}

internal class WriteFakeOutboxDao : OutboxDao {
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

    override suspend fun listQuarantined(): List<OutboxEntity> =
        entries.values.filter { it.quarantined }.sortedBy { it.createdAt }

    override suspend fun requeue(id: Long) {
        entries[id]?.takeIf { it.quarantined }?.let {
            entries[id] = it.copy(
                quarantined = false,
                attempts = 0,
                lastError = null,
                nextAttemptAt = 0L
            )
        }
    }

    override suspend fun findLive(contactId: String): OutboxEntity? =
        entries.values.firstOrNull { it.protonContactId == contactId && !it.quarantined }
    override suspend fun replaceLive(id: Long, opType: Int, payloadHash: String, createdAt: Long) {
        entries[id]?.let {
            entries[id] = it.copy(
                opType = opType,
                payloadHash = payloadHash,
                attempts = 0,
                lastError = null,
                nextAttemptAt = 0L,
                createdAt = createdAt
            )
        }
    }
    override suspend fun deleteIfUnchanged(id: Long, opType: Int, payloadHash: String) {
        entries[id]?.takeIf { it.opType == opType && it.payloadHash == payloadHash }?.let { entries.remove(id) }
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

internal class WriteFakeContactMapDao : ContactMapDao {
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
    override suspend fun deleteByProtonIds(ids: List<String>) { ids.forEach { rows.remove(it) } }
    override suspend fun setMergeBase(id: String, sealed: ByteArray?) {
        rows[id]?.let { rows[id] = it.copy(lastKnownServerPayload = sealed) }
    }
    override suspend fun mergeBase(id: String): ByteArray? = rows[id]?.lastKnownServerPayload
    override suspend fun markClean(id: String, now: Long) {
        rows[id]?.let {
            rows[id] = it.copy(syncStatus = ContactMapEntity.Status.CLEAN, lastError = null, lastSyncedAt = now)
        }
    }
    override suspend fun markConflict(id: String, error: String) {
        rows[id]?.let { rows[id] = it.copy(syncStatus = ContactMapEntity.Status.CONFLICT, lastError = error) }
    }
    override suspend fun forceRefetch(id: String) {
        rows[id]?.let { rows[id] = it.copy(modifyTime = 0L, contentHash = "") }
    }
    override suspend fun deleteAll() { rows.clear() }
}
