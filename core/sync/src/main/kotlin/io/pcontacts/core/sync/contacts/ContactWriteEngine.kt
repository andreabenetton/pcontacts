// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.sync.contacts

import android.accounts.Account
import io.pcontacts.core.contactswriter.ContactRow
import io.pcontacts.core.contactswriter.DirtyContact
import io.pcontacts.core.contactswriter.DirtyContactReader
import io.pcontacts.core.contactswriter.DirtyFlagClearer
import io.pcontacts.core.contactswriter.RawContactDataReader
import io.pcontacts.core.logging.Logger
import io.pcontacts.core.logging.NoOpSink
import io.pcontacts.core.logging.RedactingLogger
import io.pcontacts.core.proton.api.contacts.BulkDeleteRequest
import io.pcontacts.core.proton.api.contacts.ContactCardBundle
import io.pcontacts.core.proton.api.contacts.CreateContactsRequest
import io.pcontacts.core.proton.api.contacts.ProtonContactsApi
import io.pcontacts.core.proton.api.contacts.UpdateContactRequest
import io.pcontacts.core.protoncontacts.ContactSerializer
import io.pcontacts.core.protoncontacts.DecryptedContact
import io.pcontacts.core.storage.db.dao.ContactMapDao
import io.pcontacts.core.storage.db.dao.OutboxDao
import io.pcontacts.core.storage.db.entity.ContactMapEntity
import io.pcontacts.core.storage.db.entity.OutboxEntity
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import retrofit2.HttpException
import java.io.IOException
import kotlin.math.min

/**
 * Drains the persistent outbox (ADR-0017 §5B) by pushing pending
 * CREATE / UPDATE / DELETE mutations to the Proton API. Called by the
 * SyncAdapter **before** the pull engine (push-before-pull per
 * ADR-0017 §7B).
 *
 * Concurrency is bounded by a [Semaphore] with [MAX_CONCURRENT_PUSHES]
 * permits (ADR-0017 §4A). Each entry is processed independently; one
 * failure does not abort the run.
 *
 * Error classification:
 *   - Transient (5xx, 429, [IOException]) → [OutboxDao.recordFailure]
 *     with exponential backoff.
 *   - Permanent (4xx except 429) → [OutboxDao.quarantine].
 *
 * The [readLocalContact] seam is wired by Stage 3 to
 * `RawContactDataReader` + [RowToDecryptedContact]; until then it
 * returns null (quarantining the entry) which is safe because the
 * outbox is empty until Stage 3 populates it.
 */
class ContactWriteEngine(
    private val contactsApi: ProtonContactsApi,
    private val serializer: ContactSerializer,
    private val outboxDao: OutboxDao,
    private val contactMapDao: ContactMapDao,
    private val readLocalContact: suspend (protonContactId: String) -> DecryptedContact? = { null },
    private val readDirtyContacts: suspend (Account) -> List<DirtyContact> = { emptyList() },
    private val readContactRow: suspend (rawContactId: Long, sourceId: String) -> ContactRow? = { _, _ -> null },
    private val clearDirtyFlag: suspend (Account, Long) -> Unit = { _, _ -> },
    private val clock: () -> Long = System::currentTimeMillis,
    private val logger: Logger = RedactingLogger(tag = "ContactWrite", sink = NoOpSink)
) {

    /**
     * Scans for locally-modified contacts (DIRTY=1 or DELETED=1) and
     * populates the outbox with corresponding CREATE / UPDATE / DELETE
     * entries. Called before [push] in each sync run (ADR-0017 §1C).
     *
     * Returns the number of outbox entries created.
     */
    suspend fun detectChanges(account: Account): Int {
        val dirty = readDirtyContacts(account)
        if (dirty.isEmpty()) return 0

        var enqueued = 0
        for (dc in dirty) {
            val created = enqueueChange(account, dc)
            if (created) enqueued++
            clearDirtyFlag(account, dc.rawContactId)
        }
        return enqueued
    }

    private suspend fun enqueueChange(account: Account, dc: DirtyContact): Boolean {
        if (dc.isDeleted) {
            val protonId = dc.sourceId ?: return false
            if (outboxDao.findByContact(protonId).any { it.opType == OutboxEntity.OpType.DELETE }) {
                return false
            }
            outboxDao.insert(
                OutboxEntity(
                    protonContactId = protonId,
                    opType = OutboxEntity.OpType.DELETE,
                    payloadHash = "",
                    createdAt = clock()
                )
            )
            return true
        }

        val isCreate = dc.sourceId == null
        if (isCreate) {
            val localId = "local-${dc.rawContactId}"
            val row = readContactRow(dc.rawContactId, localId) ?: return false
            val hash = EmailSyncHash.compute(row)
            outboxDao.insert(
                OutboxEntity(
                    protonContactId = localId,
                    opType = OutboxEntity.OpType.CREATE,
                    payloadHash = hash,
                    createdAt = clock()
                )
            )
            return true
        }

        val protonId = dc.sourceId!!
        val row = readContactRow(dc.rawContactId, protonId) ?: return false
        val hash = EmailSyncHash.compute(row)
        val mapping = contactMapDao.findByProtonId(protonId)
        if (mapping != null && mapping.contentHash == hash) {
            return false
        }
        if (outboxDao.findByContact(protonId).any {
                it.opType == OutboxEntity.OpType.UPDATE && it.payloadHash == hash
            }
        ) {
            return false
        }
        outboxDao.insert(
            OutboxEntity(
                protonContactId = protonId,
                opType = OutboxEntity.OpType.UPDATE,
                payloadHash = hash,
                createdAt = clock()
            )
        )
        return true
    }

    suspend fun push(): WriteReport {
        val ready = outboxDao.listReady(clock())
        if (ready.isEmpty()) return WriteReport.EMPTY

        val semaphore = Semaphore(MAX_CONCURRENT_PUSHES)
        val results = coroutineScope {
            ready.map { entry ->
                async { semaphore.withPermit { pushEntry(entry) } }
            }.awaitAll()
        }

        return results.fold(WriteReport.EMPTY) { acc, r -> acc + r }
    }

    private suspend fun pushEntry(entry: OutboxEntity): WriteReport = when (entry.opType) {
        OutboxEntity.OpType.DELETE -> pushDelete(entry)
        OutboxEntity.OpType.UPDATE -> pushUpdate(entry)
        OutboxEntity.OpType.CREATE -> pushCreate(entry)
        else -> {
            logger.warn { "unknown outbox op_type=${entry.opType}, quarantining" }
            outboxDao.quarantine(entry.id, "unknown op_type=${entry.opType}")
            WriteReport(quarantined = 1)
        }
    }

    private suspend fun pushDelete(entry: OutboxEntity): WriteReport {
        val now = clock()
        if (entry.createdAt + GRACE_PERIOD_MS > now) {
            return WriteReport(skippedGrace = 1)
        }
        return try {
            contactsApi.deleteContacts(BulkDeleteRequest(ids = listOf(entry.protonContactId)))
            contactMapDao.deleteByProtonId(entry.protonContactId)
            outboxDao.deleteById(entry.id)
            WriteReport(pushed = 1, deleted = 1)
        } catch (e: Exception) {
            handleFailure(entry, e)
        }
    }

    private suspend fun pushUpdate(entry: OutboxEntity): WriteReport {
        val contact = readLocalContact(entry.protonContactId)
        if (contact == null) {
            outboxDao.quarantine(entry.id, "contact not found locally")
            return WriteReport(quarantined = 1)
        }
        return try {
            val cards = serializer.serialize(contact)
            contactsApi.updateContact(entry.protonContactId, UpdateContactRequest(cards = cards))
            val existing = contactMapDao.findByProtonId(entry.protonContactId)
            if (existing != null) {
                contactMapDao.upsert(
                    existing.copy(
                        syncStatus = ContactMapEntity.Status.CLEAN,
                        lastKnownServerPayloadHash = entry.payloadHash,
                        lastSyncedAt = clock()
                    )
                )
            }
            outboxDao.deleteById(entry.id)
            WriteReport(pushed = 1, updated = 1)
        } catch (e: Exception) {
            handleFailure(entry, e)
        }
    }

    private suspend fun pushCreate(entry: OutboxEntity): WriteReport {
        val contact = readLocalContact(entry.protonContactId)
        if (contact == null) {
            outboxDao.quarantine(entry.id, "contact not found locally")
            return WriteReport(quarantined = 1)
        }
        return try {
            val cards = serializer.serialize(contact)
            val response = contactsApi.createContacts(
                CreateContactsRequest(contacts = listOf(ContactCardBundle(cards = cards)))
            )
            val serverContact = response.responses.firstOrNull()?.response?.contact
            val existing = contactMapDao.findByProtonId(entry.protonContactId)
            if (existing != null && serverContact != null) {
                contactMapDao.deleteByProtonId(entry.protonContactId)
                contactMapDao.upsert(
                    existing.copy(
                        protonContactId = serverContact.id,
                        protonUid = serverContact.uid,
                        syncStatus = ContactMapEntity.Status.CLEAN,
                        lastKnownServerPayloadHash = entry.payloadHash,
                        lastSyncedAt = clock()
                    )
                )
            }
            outboxDao.deleteByContact(entry.protonContactId)
            WriteReport(pushed = 1, created = 1)
        } catch (e: Exception) {
            handleFailure(entry, e)
        }
    }

    private suspend fun handleFailure(entry: OutboxEntity, e: Exception): WriteReport {
        val httpCode = (e as? HttpException)?.code()
        val isTransient = e is IOException || httpCode == 429 || (httpCode != null && httpCode >= 500)

        if (isTransient) {
            val nextAttempts = entry.attempts + 1
            val backoffMs = min(nextAttempts.toLong() * nextAttempts * 30_000L, MAX_BACKOFF_MS)
            outboxDao.recordFailure(
                id = entry.id,
                attempts = nextAttempts,
                error = e.javaClass.simpleName,
                nextAt = clock() + backoffMs
            )
            return WriteReport(failed = 1)
        }

        outboxDao.quarantine(entry.id, "${e.javaClass.simpleName}: ${httpCode ?: "no code"}")
        return WriteReport(quarantined = 1)
    }

    companion object {
        const val MAX_CONCURRENT_PUSHES = 4
        const val GRACE_PERIOD_MS = 3_600_000L
        const val MAX_BACKOFF_MS = 3_600_000L
    }
}
