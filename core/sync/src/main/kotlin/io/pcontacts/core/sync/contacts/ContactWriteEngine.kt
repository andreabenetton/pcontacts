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
import io.pcontacts.core.proton.api.contacts.BulkDeleteResponse
import io.pcontacts.core.proton.api.contacts.ContactCardBundle
import io.pcontacts.core.proton.api.contacts.ContactCardDto
import io.pcontacts.core.proton.api.contacts.ContactDto
import io.pcontacts.core.proton.api.contacts.ContactsMetadataPager
import io.pcontacts.core.proton.api.contacts.CreateContactsRequest
import io.pcontacts.core.proton.api.contacts.ProtonContactsApi
import io.pcontacts.core.proton.api.contacts.UpdateContactRequest
import io.pcontacts.core.proton.api.http.HumanVerificationRequiredException
import io.pcontacts.core.proton.api.http.ProtonApiException
import io.pcontacts.core.proton.api.http.ProtonCodeInterceptor
import io.pcontacts.core.protoncontacts.ContactPatch
import io.pcontacts.core.protoncontacts.ContactSerializer
import io.pcontacts.core.protoncontacts.DecryptedContact
import io.pcontacts.core.protoncontacts.PhotoHash
import io.pcontacts.core.storage.InMemoryMergeBaseStore
import io.pcontacts.core.storage.MergeBaseStore
import io.pcontacts.core.storage.db.dao.ContactMapDao
import io.pcontacts.core.storage.db.dao.OutboxDao
import io.pcontacts.core.storage.db.dao.OutboxEnqueue
import io.pcontacts.core.storage.db.entity.ContactMapEntity
import io.pcontacts.core.storage.db.entity.OutboxEntity
import io.pcontacts.core.sync.contacts.merge.MergeBaseCodec
import io.pcontacts.core.sync.contacts.merge.ThreeWayMerger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.firstOrNull
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
 * Every UPDATE is a three-way merge (ADR-0017 §3, as amended): the
 * persisted server state in [mergeBases], the server-current contact
 * and the local row. No base, no server state, or a same-field
 * conflict all end as a user-visible CONFLICT on the mapping — never a
 * merge against an empty base and never a local-wins push. A
 * FORCE_UPDATE (the user chose the phone version) skips the merge.
 *
 * The outbox holds one live row per contact; [push] still groups by
 * contact and runs at most one row per contact, with groups bounded by
 * a [Semaphore] of [MAX_CONCURRENT_PUSHES] permits (ADR-0017 §4A). One
 * failure does not abort the run. After a push the local row is read
 * again: if it changed meanwhile, the change is re-queued (§5).
 *
 * Error classification:
 *   - Transient (5xx, 429, [IOException]) → [OutboxDao.recordFailure]
 *     with exponential backoff.
 *   - Permanent (4xx except 429) → [OutboxDao.quarantine].
 */
// Many injectable seams by design (readers, writers, API, clock, logger)
// so the whole engine stays pure-JVM testable; the count is structural.
@Suppress("LongParameterList", "TooManyFunctions")
class ContactWriteEngine(
    private val contactsApi: ProtonContactsApi,
    private val serializer: ContactSerializer,
    private val outboxDao: OutboxDao,
    private val contactMapDao: ContactMapDao,
    private val mergeBases: MergeBaseStore = InMemoryMergeBaseStore(),
    private val readDirtyContacts: suspend (Account) -> List<DirtyContact> = { emptyList() },
    private val readContactRow: suspend (rawContactId: Long, sourceId: String) -> ContactRow? = { _, _ -> null },
    private val clearDirtyFlag: suspend (Account, Long) -> Unit = { _, _ -> },
    private val writeSourceId: suspend (Account, Long, String) -> Unit = { _, _, _ -> },
    /** Server-current contact; throws on transport failure (handled like any push failure), null if there is none. */
    private val fetchServerContact: suspend (protonContactId: String) -> DecryptedContact? = { null },
    private val clock: () -> Long = System::currentTimeMillis,
    private val logger: Logger = RedactingLogger(tag = "ContactWrite", sink = NoOpSink)
) {

    /**
     * Scans for locally-modified contacts (DIRTY=1 or DELETED=1) and
     * coalesces each into the outbox (ADR-0017 §1C). Called before
     * [push] in each sync run.
     *
     * Returns the number of contacts whose change entered the queue.
     */
    suspend fun detectChanges(account: Account): Int {
        val dirty = readDirtyContacts(account)
        logger.info { "detectChanges: ${dirty.size} dirty contacts" }
        if (dirty.isEmpty()) return 0

        var enqueued = 0
        for (dc in dirty) {
            val result = enqueueChange(dc)
            logger.info { "detectChanges: rawId=${dc.rawContactId} sourceId=${dc.sourceId} deleted=${dc.isDeleted} result=$result" }
            when (result) {
                EnqueueResult.ENQUEUED -> {
                    enqueued++
                    clearDirtyFlag(account, dc.rawContactId)
                }
                EnqueueResult.SKIPPED -> clearDirtyFlag(account, dc.rawContactId)
                EnqueueResult.FAILED -> Unit
            }
        }
        return enqueued
    }

    private enum class EnqueueResult { ENQUEUED, SKIPPED, FAILED }

    private suspend fun enqueueChange(dc: DirtyContact): EnqueueResult {
        val now = clock()
        if (dc.isDeleted) {
            val protonId = dc.sourceId
            if (protonId == null) {
                // Never reached Proton: whatever CREATE is queued has nothing to create any more.
                outboxDao.findLive("$LOCAL_ID_PREFIX${dc.rawContactId}")?.let { outboxDao.deleteById(it.id) }
                return EnqueueResult.SKIPPED
            }
            return outboxDao.enqueue(protonId, OutboxEntity.OpType.DELETE, "", now).toResult()
        }

        val isCreate = dc.sourceId == null
        val protonId = dc.sourceId ?: "$LOCAL_ID_PREFIX${dc.rawContactId}"
        val row = readContactRow(dc.rawContactId, protonId)
        if (row == null) {
            logger.warn { "enqueue: readContactRow returned null for rawId=${dc.rawContactId}" }
            return EnqueueResult.FAILED
        }
        val hash = EmailSyncHash.compute(row)
        if (!isCreate) {
            val mapping = contactMapDao.findByProtonId(protonId)
            if (mapping != null && mapping.contentHash == hash) {
                logger.info { "enqueue: hash unchanged, skipping (stored=${mapping.contentHash.take(8)})" }
                return EnqueueResult.SKIPPED
            }
            logger.info { "enqueue: hash differs stored=${mapping?.contentHash?.take(8)} new=${hash.take(8)}" }
        }
        val op = if (isCreate) OutboxEntity.OpType.CREATE else OutboxEntity.OpType.UPDATE
        return outboxDao.enqueue(protonId, op, hash, now).toResult()
    }

    private fun OutboxEnqueue.toResult(): EnqueueResult = when (this) {
        OutboxEnqueue.INSERTED, OutboxEnqueue.REPLACED -> EnqueueResult.ENQUEUED
        OutboxEnqueue.UNCHANGED, OutboxEnqueue.DROPPED -> EnqueueResult.SKIPPED
    }

    /**
     * [account] is needed only to write a created contact's server id
     * back onto its RawContact (ADR-0010 sync-adapter URI). Production
     * always supplies it — it is nullable so pure-outbox tests, which
     * exercise the API seams and not the provider, can call `push()`.
     */
    suspend fun push(account: Account? = null): WriteReport {
        val ready = outboxDao.listReady(clock())
        logger.info { "push: ${ready.size} entries ready" }
        if (ready.isEmpty()) return WriteReport.EMPTY

        // One row per contact: the newest wins, older live duplicates (pre-v3 rows) go.
        val survivors = ready.groupBy { it.protonContactId }.values.map { group ->
            val newest = group.maxBy { it.id }
            group.filter { it.id != newest.id }.forEach { outboxDao.deleteById(it.id) }
            newest
        }

        val semaphore = Semaphore(MAX_CONCURRENT_PUSHES)
        val results = coroutineScope {
            survivors.map { entry ->
                async { semaphore.withPermit { pushEntry(entry, account) } }
            }.awaitAll()
        }

        return results.fold(WriteReport.EMPTY) { acc, r -> acc + r }
    }

    private suspend fun pushEntry(entry: OutboxEntity, account: Account?): WriteReport = when (entry.opType) {
        OutboxEntity.OpType.DELETE -> pushDelete(entry)
        OutboxEntity.OpType.UPDATE, OutboxEntity.OpType.FORCE_UPDATE -> pushUpdate(entry)
        OutboxEntity.OpType.CREATE -> pushCreate(entry, account)
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
            val response = contactsApi.deleteContacts(BulkDeleteRequest(ids = listOf(entry.protonContactId)))
            requireItemAccepted(response, entry.protonContactId)
            contactMapDao.deleteByProtonId(entry.protonContactId)
            outboxDao.deleteById(entry.id)
            WriteReport(pushed = 1, deleted = 1)
        } catch (e: HumanVerificationRequiredException) {
            // Don't quarantine the outbox entry — the user just needs to
            // solve captcha and the next push will succeed. Surface to the
            // SyncAdapter which fires the HV notification.
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFailure(entry, e)
        }
    }

    private suspend fun pushUpdate(entry: OutboxEntity): WriteReport {
        val id = entry.protonContactId
        val mapping = contactMapDao.findByProtonId(id)
        val row = mapping?.let { readContactRow(it.androidRawContactId, id) }
        if (mapping == null || row == null) {
            logger.warn { "pushUpdate: contact not found locally, quarantining id=${entry.id}" }
            outboxDao.quarantine(entry.id, "contact not found locally")
            return WriteReport(quarantined = 1)
        }
        val pushedHash = EmailSyncHash.compute(row)
        val local = RowToDecryptedContact.convert(row, id, mapping.protonUid)

        return try {
            val outcome = resolvePayload(entry, mapping, local)
            if (outcome == null) {
                WriteReport(conflicted = 1)
            } else {
                val (payload, cards) = outcome
                if (cards == null) {
                    logger.info { "push: nothing left to change on the server idTag=${id.hashCode()}" }
                } else {
                    contactsApi.updateContact(id, UpdateContactRequest(cards = cards))
                }
                // [A] Proton stores exactly the cards it accepted, so the payload is the
                // server state until the next pull re-captures it. Column updates only:
                // an upsert here would overwrite the sealed base.
                val after = completeEntry(entry, mapping.androidRawContactId, pushedHash)
                MergeBaseCodec.save(mergeBases, id, payload, localPhotoHash = after?.photo?.data?.let(PhotoHash::of))
                contactMapDao.markClean(id, clock())
                WriteReport(pushed = 1, updated = 1)
            }
        } catch (e: HumanVerificationRequiredException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn { "pushUpdate: failed ${e.javaClass.simpleName}" }
            handleFailure(entry, e)
        }
    }

    /**
     * What to push: the merged contact and the cards to PUT — the
     * server's current cards with the change set applied (ADR-0017 §2,
     * Choice 2C), or null cards when the merge left nothing to change.
     * Null altogether when the contact became a conflict: the mapping
     * carries the reason and the outbox row is gone, so the user's
     * decision (ConflictResolver) is what happens next.
     */
    private suspend fun resolvePayload(
        entry: OutboxEntity,
        mapping: ContactMapEntity,
        local: DecryptedContact
    ): Pair<DecryptedContact, List<ContactCardDto>?>? {
        val id = entry.protonContactId
        val server = fetchServerContact(id) ?: return markConflict(entry, "server state unavailable")
        val serverCanonical = MergeBaseCodec.canonical(server) ?: return markConflict(entry, "server state unavailable")
        val merged = if (entry.opType == OutboxEntity.OpType.FORCE_UPDATE) {
            local
        } else {
            mergeForPush(entry, serverCanonical, local) ?: return null
        }
        val patch = ContactPatch.diff(serverCanonical, merged)
        val cards = when {
            patch.isEmpty -> null
            // A server contact always has cards; a fixture without them gets the create layout.
            server.cards.isEmpty() -> serializer.serialize(merged)
            else -> serializer.serialize(
                server.cards,
                patch,
                mapping.protonUid?.takeIf { it.isNotBlank() } ?: ContactSerializer.fallbackUid(id)
            )
        }
        return merged to cards
    }

    private suspend fun mergeForPush(
        entry: OutboxEntity,
        server: DecryptedContact,
        local: DecryptedContact
    ): DecryptedContact? {
        val base = MergeBaseCodec.load(mergeBases, entry.protonContactId) ?: return markConflict(entry, "no merge base")
        return when (val result = ThreeWayMerger.merge(ThreeWayMerger.MergeInput(base, server, local))) {
            is ThreeWayMerger.MergeResult.AutoMerged -> result.merged
            is ThreeWayMerger.MergeResult.Conflicted ->
                markConflict(entry, result.conflicts.joinToString { it.fieldName })
        }
    }

    private suspend fun markConflict(entry: OutboxEntity, reason: String): Nothing? {
        contactMapDao.markConflict(entry.protonContactId, "conflict: $reason")
        outboxDao.deleteIfUnchanged(entry.id, entry.opType, entry.payloadHash)
        logger.warn { "pushUpdate: conflict ($reason) idTag=${entry.protonContactId.hashCode()}" }
        return null
    }

    /**
     * ADR-0017 §5: the row is done only if it still describes what was
     * pushed; if the contact changed meanwhile, the new state is queued.
     * Returns the row as it is now (its photo digest goes into the base).
     */
    private suspend fun completeEntry(entry: OutboxEntity, rawContactId: Long, pushedHash: String): ContactRow? {
        outboxDao.deleteIfUnchanged(entry.id, entry.opType, entry.payloadHash)
        val now = readContactRow(rawContactId, entry.protonContactId)
        val nowHash = now?.let(EmailSyncHash::compute)
        if (nowHash != null && nowHash != pushedHash) {
            logger.info { "push: contact changed during push, re-queued idTag=${entry.protonContactId.hashCode()}" }
            outboxDao.enqueue(entry.protonContactId, OutboxEntity.OpType.UPDATE, nowHash, clock())
        }
        return now
    }

    private suspend fun pushCreate(entry: OutboxEntity, account: Account?): WriteReport {
        val localId = entry.protonContactId
        val rawContactId = resolveRawContactId(localId, contactMapDao)
        val row = rawContactId?.let { readContactRow(it, localId) }
        if (rawContactId == null || row == null) {
            outboxDao.quarantine(entry.id, "contact not found locally")
            return WriteReport(quarantined = 1)
        }
        val pushedHash = EmailSyncHash.compute(row)
        // A CREATE has no stored UID — the serializer mints one.
        val contact = RowToDecryptedContact.convert(row, localId, null)
        return try {
            val cards = serializer.serialize(contact)
            val serverContact = createOnServer(cards, ContactSerializer.fallbackUid(localId))
            val existing = contactMapDao.findByProtonId(localId)
            if (existing != null && serverContact != null) {
                contactMapDao.deleteByProtonId(localId)
                contactMapDao.upsert(
                    existing.copy(
                        protonContactId = serverContact.id,
                        protonUid = serverContact.uid,
                        syncStatus = ContactMapEntity.Status.CLEAN,
                        lastSyncedAt = clock()
                    )
                )
            }
            if (serverContact != null && account != null) writeSourceId(account, rawContactId, serverContact.id)
            outboxDao.deleteByContact(localId)
            val nowRow = readContactRow(rawContactId, localId)
            if (serverContact != null) {
                MergeBaseCodec.save(
                    mergeBases,
                    serverContact.id,
                    contact,
                    localPhotoHash = nowRow?.photo?.data?.let(PhotoHash::of)
                )
            }
            val nowHash = nowRow?.let(EmailSyncHash::compute)
            if (serverContact != null && nowHash != null && nowHash != pushedHash) {
                outboxDao.enqueue(serverContact.id, OutboxEntity.OpType.UPDATE, nowHash, clock())
            }
            WriteReport(pushed = 1, created = 1)
        } catch (e: HumanVerificationRequiredException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFailure(entry, e)
        }
    }

    /** `[V]` the batch envelope is 1001 with one Code per item; anything but 1000 is a refusal. */
    private fun requireItemAccepted(response: BulkDeleteResponse, id: String) {
        val itemCode = response.responses.firstOrNull { it.id == id }?.response?.code ?: return
        if (itemCode != ProtonCodeInterceptor.SUCCESS_CODE) throw ProtonApiException(itemCode, null)
    }

    /**
     * POSTs the cards and returns the created contact. A create is not
     * idempotent by request, but it is by UID: the serializer mints a
     * deterministic UID per local id, so when the response is lost (an
     * [IOException] after Proton may have committed) or the item is
     * refused (`[U]` the duplicate-UID code is not pinned; recovery is
     * keyed on the UID, not the code), the contact is looked up by that
     * UID before the attempt counts as failed.
     */
    private suspend fun createOnServer(cards: List<ContactCardDto>, uid: String): ContactDto? {
        val response = try {
            contactsApi.createContacts(CreateContactsRequest(contacts = listOf(ContactCardBundle(cards = cards))))
        } catch (e: IOException) {
            return findByUid(uid) ?: throw e
        }
        val item = response.responses.firstOrNull()?.response ?: return null
        if (item.code == ProtonCodeInterceptor.SUCCESS_CODE) return item.contact
        logger.warn { "create refused with Code:${item.code}; looking the contact up by UID" }
        return findByUid(uid) ?: throw ProtonApiException(item.code, null)
    }

    /** The server contact carrying [uid], or null; a failed lookup is not a failure of the create. */
    private suspend fun findByUid(uid: String): ContactDto? = try {
        ContactsMetadataPager(contactsApi).metadata().firstOrNull { it.uid == uid }
            ?.let { ContactDto(id = it.id, uid = it.uid) }
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        logger.warn { "UID lookup after a create failed: ${t.javaClass.simpleName}" }
        null
    }

    private suspend fun handleFailure(entry: OutboxEntity, e: Exception): WriteReport {
        val httpCode = (e as? HttpException)?.code()
        val isTransient = e is IOException || httpCode == 429 || (httpCode != null && httpCode >= 500)
        // A stable, non-sensitive reason. NOT e.javaClass.simpleName: R8
        // minifies it to a meaningless letter on release builds (the "p:
        // 400" users saw), and e.message can carry contact content.
        val reason = when {
            httpCode != null -> "HTTP $httpCode"
            e is ProtonApiException -> "Proton code ${e.protonCode}"
            e is IOException -> "network error"
            else -> "internal error"
        }

        if (isTransient) {
            val nextAttempts = entry.attempts + 1
            val backoffMs = min(nextAttempts.toLong() * nextAttempts * 30_000L, MAX_BACKOFF_MS)
            outboxDao.recordFailure(
                id = entry.id,
                attempts = nextAttempts,
                error = reason,
                nextAt = clock() + backoffMs
            )
            return WriteReport(failed = 1)
        }

        outboxDao.quarantine(entry.id, reason)
        return WriteReport(quarantined = 1)
    }

    companion object {
        const val MAX_CONCURRENT_PUSHES = 4
        const val GRACE_PERIOD_MS = 3_600_000L
        const val MAX_BACKOFF_MS = 3_600_000L
    }
}
