// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.sync.contacts

import android.accounts.Account
import io.pcontacts.core.contactswriter.ApplyResult
import io.pcontacts.core.contactswriter.ContactRow
import io.pcontacts.core.contactswriter.ExistingRawContacts
import io.pcontacts.core.contactswriter.ProtonLabel
import io.pcontacts.core.contactswriter.RawContactDiffer
import io.pcontacts.core.contactswriter.RawContactOpIntent
import io.pcontacts.core.logging.Logger
import io.pcontacts.core.logging.NoOpSink
import io.pcontacts.core.logging.RedactingLogger
import io.pcontacts.core.proton.api.contacts.ContactsMetadataPager
import io.pcontacts.core.proton.api.contacts.ProtonContactsApi
import io.pcontacts.core.proton.api.http.HumanVerificationRequiredException
import io.pcontacts.core.proton.api.labels.LabelType
import io.pcontacts.core.proton.api.labels.ProtonLabelsApi
import io.pcontacts.core.protoncontacts.ContactProcessor
import io.pcontacts.core.protoncontacts.DecryptedContact
import io.pcontacts.core.storage.db.dao.ContactMapDao
import io.pcontacts.core.storage.db.entity.ContactMapEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.toList

/**
 * Full-decrypt sync engine (plan §17 task 17, end-to-end). Per-contact
 * flow:
 *   1. Walk /contacts/v4/contacts → Map<sourceId, server modifyTime>.
 *   2. For each ID, cheap-skip if storedMapping.modifyTime ≥ server's
 *      (no fetch, no decrypt — just refresh `last_synced_at`).
 *   3. Otherwise GET /contacts/v4/contacts/{id} → full Cards[].
 *   4. ContactProcessor decrypts + merges Cards → DecryptedContact.
 *   5. DecryptedContactToRow projects to the MVP ContactRow shape.
 *   6. Hash-compare against the stored content_hash; skip writes when
 *      unchanged. Mapping rows still get a `last_synced_at` /
 *      `is_verified` / `proton_uid` refresh.
 *   7. RawContactDiffer + applier produce + apply ContactsContract ops.
 *   8. Reconcile the Room mapping with the post-apply RawContacts._IDs.
 *
 * Same IO seams and same idempotency
 * contract. Differs in that the displayName written to
 * ContactsContract comes from the decrypted SIGNED card's FN rather
 * than the email row's denormalised Name, and the per-contact
 * `is_verified` flag reflects whether every signed card verified.
 *
 * The two-tier skip (modifyTime first, content_hash second) means
 * second-run cost on an unchanged account is one /contacts listing
 * call — no per-contact fetches, no decrypt cycles.
 *
 * Self-healing invariant (ADR-0022): ContactsProvider is authoritative
 * for which local RawContacts exist; the Room mapping is only sync
 * metadata. Neither skip tier may fire when the provider no longer
 * holds a live row for the contact — the contact is refetched and the
 * RawContact recreated. A stale `androidRawContactId` is repaired to
 * the provider's live row, and duplicate rows sharing one SOURCE_ID
 * under our account are reconciled to a deterministic survivor.
 */
// The constructor parameter list is the DI seam surface (Bootstrap-wired
// function seams per CLAUDE.md); splitting it would hide the wiring.
@Suppress("LongParameterList")
class ContactDetailSyncEngine(
    private val metadataPager: ContactsMetadataPager,
    private val contactsApi: ProtonContactsApi,
    private val labelsApi: ProtonLabelsApi,
    private val processor: ContactProcessor,
    private val contactMapDao: ContactMapDao,
    private val readExisting: suspend (Account) -> ExistingRawContacts,
    private val applyIntents: suspend (Account, List<RawContactOpIntent>) -> ApplyResult,
    /**
     * True when a non-quarantined DELETE for this Proton contact is
     * queued in the outbox — the user deleted it locally and the
     * deletion hasn't been pushed yet. Guards self-healing recovery
     * against resurrecting an intentional deletion whose tombstone was
     * purged externally. Default false keeps fixtures that don't
     * exercise deletion simple.
     */
    private val hasPendingDelete: suspend (protonContactId: String) -> Boolean = { false },
    /** True while any non-quarantined outbox row exists for the contact; such a mapping is never an orphan. */
    private val hasLiveOutboxRow: suspend (protonContactId: String) -> Boolean = { false },
    /**
     * Reconciles ContactsContract.Groups rows for `account` against the
     * server-side label set; returns `Map<proton label id, local Groups._ID>`.
     * Defaults to a no-op that yields empty map — keeps the constructor
     * backward-compatible for any test fixture that doesn't care about
     * groups.
     */
    private val reconcileGroups: suspend (Account, List<ProtonLabel>) -> Map<String, Long> = { _, _ -> emptyMap() },
    /**
     * (contacts processed so far, server total) — reported once the
     * total is known, then every [PROGRESS_EVERY] contacts and at the
     * end of the per-contact pass, so a UI can show "120 of 898".
     */
    private val onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    /**
     * Records the server state just written locally as the contact's
     * merge base (ADR-0017 §3); the write engine merges against it.
     * Called after the mapping row is upserted, never before.
     */
    private val saveMergeBase: suspend (protonContactId: String, DecryptedContact) -> Unit = { _, _ -> },
    /**
     * Digest of the photo bytes the provider holds for the RawContact,
     * read back after a write: the provider re-encodes inline photos, so
     * only this digest can tell a later local photo change apart from
     * the re-encoding (ADR-0017 second amendment).
     */
    private val readLocalPhotoHash: suspend (rawContactId: Long) -> String? = { null },
    /**
     * The GroupMembership rows the provider currently holds for a
     * RawContact. Consulted only while the label state is unknown (the
     * Labels call failed), so a rewrite keeps the memberships it cannot
     * recompute instead of wiping them.
     */
    private val readGroupRowIds: suspend (rawContactId: Long) -> List<Long> = { emptyList() },
    /**
     * Whether the outbox holds any row for the contact, queued or quarantined: the phone still
     * owns a change to it, so the pull must neither overwrite nor delete the row.
     */
    private val hasLocalMutation: suspend (protonContactId: String) -> Boolean = { false },
    private val clock: () -> Long = System::currentTimeMillis,
    private val logger: Logger = RedactingLogger(tag = "ContactDetailSync", sink = NoOpSink)
) {

    // One pass per sync run, in the order the steps must happen; the throws
    // only rethrow cancellation and the 9001 gate (previously baselined).
    @Suppress("ThrowsCount", "LongMethod", "CyclomaticComplexMethod")
    suspend fun sync(account: Account): SyncReport {
        logger.info { "contact-detail sync start account=${account.name}" }

        // 1a. Labels: fetch + reconcile ContactsContract.Groups before any
        //     contact write so per-contact GroupMembership rows have a
        //     valid local Groups._ID to point at. Failure is non-fatal but
        //     leaves the group state UNKNOWN (null, not empty): contacts
        //     written this run keep the memberships the provider already
        //     holds and are marked for a refetch, so the memberships are
        //     recomputed once labels are back. An empty label set is a
        //     known state and does delete local groups.
        val labelMap: Map<String, Long>? = try {
            val labels = labelsApi.listLabels(LabelType.CONTACT_GROUP).labels
                .map { ProtonLabel(id = it.id, name = it.name.ifBlank { it.id }) }
            reconcileGroups(account, labels)
        } catch (e: HumanVerificationRequiredException) {
            // Don't degrade to "sync without groups" — abort the whole sync so
            // ProtonSyncAdapter shows the captcha notification.
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            logger.warn(t) { "labels fetch / reconcile failed; group state unknown this run, memberships kept" }
            null
        }

        // 1b. Cheap metadata enumeration → ID + ModifyTime + labelIds.
        val metadata = metadataPager.metadata().toList()
        val serverModifyTimes: Map<String, Long> = metadata.associate { it.id to it.modifyTime }
        val serverLabelIds: Map<String, List<String>> =
            metadata.associate { it.id to it.labelIds }
        val serverSourceIds = serverModifyTimes.keys
        onProgress(0, serverSourceIds.size)

        // 2. Local state. ContactsProvider is authoritative for which
        //    RawContacts exist; the Room mapping is only sync metadata
        //    and converges to provider reality (repairMapping below).
        val existingState = readExisting(account)
        val storedMappings: Map<String, ContactMapEntity> = contactMapDao.listLive()
            .associateBy { it.protonContactId }

        // 2a. Orphans (ADR-0022): a mapping whose contact is on neither
        //     side any more — typically left by a batch that failed after
        //     an earlier chunk deleted the row — is sync metadata for
        //     nothing. A queued change (a local-<id> create, any live
        //     outbox row) keeps its mapping.
        val orphans = storedMappings.keys.filter { id ->
            id !in serverSourceIds && id !in existingState.rowsBySourceId &&
                !id.startsWith(LOCAL_ID_PREFIX) && !hasLiveOutboxRow(id)
        }
        if (orphans.isNotEmpty()) {
            logger.warn { "dropping ${orphans.size} orphan mapping(s)" }
            contactMapDao.deleteByProtonIds(orphans)
        }

        // 2b. Canonical SOURCE_ID → _ID view + duplicate reconciliation.
        //     Several rows sharing one SOURCE_ID under our account is an
        //     invalid state (seen in the field after an external
        //     duplicate-cleanup pass); delete the extras, keep a
        //     deterministic survivor. DeleteRawContact uses the
        //     sync-adapter URI, so the cleanup leaves no DIRTY/DELETED
        //     state and never queues a Proton DELETE.
        val existing = HashMap<String, Long>(existingState.rowsBySourceId.size)
        val dedupeIntents = ArrayList<RawContactOpIntent>()
        for (sourceId in existingState.rowsBySourceId.keys) {
            val preferred = storedMappings[sourceId]?.androidRawContactId
            val canonical = existingState.canonicalId(sourceId, preferred) ?: continue
            existing[sourceId] = canonical
            val extras = existingState.duplicateIds(sourceId, canonical)
            if (extras.isNotEmpty()) {
                logger.warn {
                    "duplicate RawContacts (${extras.size} extras) reconciled to " +
                        "rawContactId=$canonical idTag=${sourceId.hashCode()}"
                }
                extras.mapTo(dedupeIntents) { RawContactOpIntent.DeleteRawContact(it) }
            }
        }

        // 3. Per-ID: cheap-skip (modifyTime) → fetch → decrypt → project →
        //    hash-skip (content_hash) → target list.
        val target = ArrayList<ContactRow>(serverSourceIds.size)
        val perContactMeta = HashMap<String, PerContactMeta>(serverSourceIds.size)
        val now = clock()
        var fetchFailures = 0
        var modifyTimeSkips = 0
        var processed = 0
        // Rows the phone still owns a change to: never overwritten or deleted by this pull.
        val protectedIds = HashSet<String>()

        for ((sourceId, serverModifyTime) in serverModifyTimes) {
            processed += 1
            if (processed % PROGRESS_EVERY == 0 || processed == serverModifyTimes.size) {
                onProgress(processed, serverModifyTimes.size)
            }
            val stored = storedMappings[sourceId]
            val liveRawId = existing[sourceId]
            val deletePending = stored != null && liveRawId == null &&
                hasPendingDelete(sourceId)
            if (deletePending) {
                // The user deleted this contact locally and the DELETE is
                // still queued (grace period) while the tombstone row is
                // already gone. Leave both sides alone — recreating it
                // here would resurrect an intentional deletion before it
                // propagates to Proton.
                logger.info { "skip: local delete pending push idTag=${sourceId.hashCode()}" }
                continue
            }
            if (liveRawId != null && stored != null && locallyOwned(stored, sourceId)) {
                // A conflict awaiting the user's decision, or a change still queued or
                // quarantined for push: the server version must not replace the row
                // the user is still the owner of (ADR-0017 §3C).
                logger.info { "skip: unresolved local state idTag=${sourceId.hashCode()}" }
                protectedIds += sourceId
                continue
            }
            val storedFormatCurrent =
                stored?.contentHash?.startsWith(EmailSyncHash.FORMAT_PREFIX) == true
            val serverUnchanged = serverModifyTime > 0L && stored != null &&
                stored.modifyTime >= serverModifyTime
            val baseKnown = stored?.lastKnownServerPayload != null
            val skipEligible = serverUnchanged && storedFormatCurrent && baseKnown
            if (skipEligible && liveRawId != null) {
                // Cheap-skip: server says unchanged AND the stored hash
                // is in the current writer format AND the merge base is
                // stored AND the provider still holds the row. If the
                // hash format has rolled (Phase 12 hash bump for the chip
                // row) or the base is missing (first pull after the v3
                // upgrade), we fall through to fetch+rewrite even when
                // the server ModifyTime hasn't advanced — otherwise the
                // one-shot migration never lands. If the row vanished
                // (external app deleted it), we fall through so the
                // contact is recreated.
                modifyTimeSkips += 1
                contactMapDao.upsert(repairMapping(stored, liveRawId).copy(lastSyncedAt = now))
                continue
            }
            if (stored != null && liveRawId == null) {
                logger.warn { "RawContact missing for mapped contact; recreating idTag=${sourceId.hashCode()}" }
            }

            val response = try {
                contactsApi.getContact(sourceId)
            } catch (e: HumanVerificationRequiredException) {
                // 9001 on /contacts/v4/contacts/{id} — every subsequent fetch
                // will hit the same gate. Abort so SyncAdapter notifies the
                // user once instead of looping N times.
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                fetchFailures += 1
                logger.error(t) { "contact skipped (fetch failed) idTag=${sourceId.hashCode()}" }
                continue
            }
            // Decrypt + project. A single malformed/undecryptable contact
            // must not abort the whole sync — skip it (counted) and carry on,
            // otherwise one bad card on a large account fails every contact.
            val (decrypted, baseRow) = try {
                val d = processor.process(response.contact)
                d to DecryptedContactToRow.convert(d)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                fetchFailures += 1
                logger.error(t) { "contact skipped (decrypt/parse failed) idTag=${sourceId.hashCode()}" }
                continue
            }
            if (baseRow == null) {
                fetchFailures += 1
                logger.warn { "contact skipped (nothing representable) idTag=${sourceId.hashCode()}" }
                continue
            }
            // Attach the contact's group memberships (translating Proton
            // LabelIDs → local Groups._ID via the reconciled labelMap); with
            // the label state unknown, keep what the provider holds.
            val groupRowIds = if (labelMap != null) {
                serverLabelIds[sourceId].orEmpty().mapNotNull { labelId -> labelMap[labelId] }
            } else {
                liveRawId?.let { readGroupRowIds(it) }.orEmpty()
            }
            val row = if (groupRowIds.isEmpty()) baseRow else baseRow.copy(groupRowIds = groupRowIds)

            val newHash = EmailSyncHash.compute(row)
            val meta = PerContactMeta(
                // Unknown group state: forget the server time so the next pull refetches the contact.
                modifyTime = if (labelMap == null) 0L else response.contact.modifyTime,
                verified = decrypted.verified,
                protonUid = decrypted.protonUid,
                hash = newHash,
                decrypted = decrypted
            )
            perContactMeta[sourceId] = meta

            if (stored?.contentHash == newHash && liveRawId != null) {
                // ModifyTime bumped but visible content unchanged, and the
                // provider still holds the row. Refresh bookkeeping; no
                // ContactsContract write needed. A matching hash means
                // "the data hasn't changed", not "the row still exists" —
                // with the row missing we fall through and recreate it.
                contactMapDao.upsert(
                    repairMapping(stored, liveRawId).copy(
                        modifyTime = meta.modifyTime,
                        isVerified = meta.verified,
                        protonUid = meta.protonUid,
                        syncStatus = ContactMapEntity.Status.CLEAN,
                        lastError = null,
                        lastSyncedAt = now
                    )
                )
                saveMergeBase(sourceId, decrypted.copy(localPhotoHash = readLocalPhotoHash(liveRawId)))
                continue
            }

            target += row
        }

        // 3b. Rows the server no longer has while the phone still owns a change to them
        //     (or a CREATE is queued for the raw row): not deleted, marked as a conflict
        //     the user settles — keep the phone's version on Proton anew, or let it go.
        val rawIdsWithQueuedCreate = storedMappings.values
            .filter { it.protonContactId.startsWith(LOCAL_ID_PREFIX) }
            .map { it.androidRawContactId }
            .toSet()
        for ((sourceId, rawId) in existing) {
            if (sourceId in serverSourceIds) continue
            val stored = storedMappings[sourceId]
            val owned = (stored != null && locallyOwned(stored, sourceId)) || rawId in rawIdsWithQueuedCreate
            if (!owned) continue
            protectedIds += sourceId
            if (stored != null && stored.lastError != SERVER_DELETED_CONFLICT) {
                logger.warn { "deleted on Proton while edited here; kept as a conflict idTag=${sourceId.hashCode()}" }
                contactMapDao.markConflict(sourceId, SERVER_DELETED_CONFLICT)
            }
        }

        // 4. Decide intents — duplicate cleanup first, then the diff. Protected rows count as
        //    present on the server so the differ leaves them alone.
        val intents = dedupeIntents + RawContactDiffer.diff(
            target = target,
            existing = existing,
            serverSourceIds = serverSourceIds + protectedIds
        )

        if (intents.isEmpty()) {
            val unchanged = (serverSourceIds.size - target.size - fetchFailures).coerceAtLeast(0)
            val unverified = contactMapDao.countUnverified()
            logger.info {
                "contact-detail sync done — no writes; " +
                    "unchanged=$unchanged modifyTimeSkips=$modifyTimeSkips fetchFailures=$fetchFailures " +
                    "unverified=$unverified"
            }
            return SyncReport(
                totalServer = serverSourceIds.size,
                inserted = 0,
                updated = 0,
                deleted = 0,
                unchanged = unchanged,
                unverifiedCount = unverified,
                failed = fetchFailures
            )
        }

        // 5. Apply ops. A chunked batch that fails leaves the earlier
        //    chunks committed; the mappings of contacts those chunks
        //    deleted are dropped here so they do not outlive their rows.
        val applyResult = try {
            applyIntents(account, intents)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            dropMappingsOfDeletedRows(account, intents)
            throw t
        }

        // 6. Reconcile mapping rows.
        val freshExisting = readExisting(account).canonicalIds()
        for (row in target) {
            val rawId = freshExisting[row.sourceId]
            if (rawId == null) {
                logger.warn { "post-apply read missed source-id; skipping mapping refresh" }
                continue
            }
            val meta = perContactMeta[row.sourceId] ?: continue
            contactMapDao.upsert(
                ContactMapEntity(
                    protonContactId = row.sourceId,
                    protonUid = meta.protonUid,
                    androidRawContactId = rawId,
                    modifyTime = meta.modifyTime,
                    contentHash = meta.hash,
                    isVerified = meta.verified,
                    deleted = false,
                    syncStatus = ContactMapEntity.Status.CLEAN,
                    lastError = null,
                    lastSyncedAt = now
                )
            )
            saveMergeBase(row.sourceId, meta.decrypted.copy(localPhotoHash = readLocalPhotoHash(rawId)))
        }
        for (intent in intents.filterIsInstance<RawContactOpIntent.DeleteContact>()) {
            contactMapDao.deleteByProtonId(intent.sourceId)
        }

        val deletedCount = intents.count { it is RawContactOpIntent.DeleteContact }
        val unchanged = (serverSourceIds.size - target.size - fetchFailures).coerceAtLeast(0)
        val unverified = contactMapDao.countUnverified()
        logger.info {
            "contact-detail sync done — inserted=${applyResult.insertedContacts} " +
                "updated=${applyResult.updatedContacts} deleted=$deletedCount " +
                "unchanged=$unchanged modifyTimeSkips=$modifyTimeSkips fetchFailures=$fetchFailures " +
                "unverified=$unverified"
        }
        return SyncReport(
            totalServer = serverSourceIds.size,
            inserted = applyResult.insertedContacts,
            updated = applyResult.updatedContacts,
            deleted = deletedCount,
            unchanged = unchanged,
            unverifiedCount = unverified,
            failed = fetchFailures
        )
    }

    /** A conflict awaiting the user, or any outbox row (queued or quarantined) for the contact. */
    private suspend fun locallyOwned(stored: ContactMapEntity, sourceId: String): Boolean =
        stored.syncStatus == ContactMapEntity.Status.CONFLICT || hasLocalMutation(sourceId)

    /**
     * Points the Room mapping at the RawContact the provider actually
     * holds. A stale androidRawContactId (e.g. an external app removed
     * and recreated the row) converges here without rewriting contact
     * data.
     */
    private fun repairMapping(stored: ContactMapEntity, liveRawId: Long): ContactMapEntity {
        if (stored.androidRawContactId == liveRawId) return stored
        logger.info {
            "mapping repaired: rawContactId ${stored.androidRawContactId} -> $liveRawId " +
                "idTag=${stored.protonContactId.hashCode()}"
        }
        return stored.copy(androidRawContactId = liveRawId)
    }

    private suspend fun dropMappingsOfDeletedRows(account: Account, intents: List<RawContactOpIntent>) {
        val present = readExisting(account).rowsBySourceId.keys
        val gone = intents.filterIsInstance<RawContactOpIntent.DeleteContact>()
            .map { it.sourceId }
            .filter { it !in present }
        if (gone.isNotEmpty()) contactMapDao.deleteByProtonIds(gone)
    }

    private data class PerContactMeta(
        val modifyTime: Long,
        val verified: Boolean,
        val protonUid: String?,
        val hash: String,
        /** Heap only, same lifetime as the target row; becomes the sealed merge base after the write. */
        val decrypted: DecryptedContact
    )
}

/** Progress callback cadence: every N contacts, plus the final one. */
private const val PROGRESS_EVERY = 10
