// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.sync.contacts

import android.accounts.Account
import io.pcontacts.core.contactswriter.ApplyResult
import io.pcontacts.core.contactswriter.ContactRow
import io.pcontacts.core.contactswriter.ProtonLabel
import io.pcontacts.core.contactswriter.RawContactDiffer
import io.pcontacts.core.contactswriter.RawContactOpIntent
import io.pcontacts.core.logging.Logger
import io.pcontacts.core.logging.NoOpSink
import io.pcontacts.core.logging.RedactingLogger
import io.pcontacts.core.proton.api.contacts.ContactsMetadataPager
import io.pcontacts.core.proton.api.contacts.ProtonContactsApi
import io.pcontacts.core.proton.api.labels.LabelType
import io.pcontacts.core.proton.api.labels.ProtonLabelsApi
import io.pcontacts.core.protoncontacts.ContactProcessor
import io.pcontacts.core.storage.db.dao.ContactMapDao
import io.pcontacts.core.storage.db.entity.ContactMapEntity
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
 * Sibling to EmailSyncEngine — same IO seams, same idempotency
 * contract. Differs in that the displayName written to
 * ContactsContract comes from the decrypted SIGNED card's FN rather
 * than the email row's denormalised Name, and the per-contact
 * `is_verified` flag reflects whether every signed card verified.
 *
 * The two-tier skip (modifyTime first, content_hash second) means
 * second-run cost on an unchanged account is one /contacts listing
 * call — no per-contact fetches, no decrypt cycles.
 */
class ContactDetailSyncEngine(
    private val metadataPager: ContactsMetadataPager,
    private val contactsApi: ProtonContactsApi,
    private val labelsApi: ProtonLabelsApi,
    private val processor: ContactProcessor,
    private val contactMapDao: ContactMapDao,
    private val readExisting: suspend (Account) -> Map<String, Long>,
    private val applyIntents: suspend (Account, List<RawContactOpIntent>) -> ApplyResult,
    /**
     * Reconciles ContactsContract.Groups rows for `account` against the
     * server-side label set; returns `Map<proton label id, local Groups._ID>`.
     * Defaults to a no-op that yields empty map — keeps the constructor
     * backward-compatible for any test fixture that doesn't care about
     * groups.
     */
    private val reconcileGroups: suspend (Account, List<ProtonLabel>) -> Map<String, Long> = { _, _ -> emptyMap() },
    private val clock: () -> Long = System::currentTimeMillis,
    private val logger: Logger = RedactingLogger(tag = "ContactDetailSync", sink = NoOpSink)
) {

    suspend fun sync(account: Account): SyncReport {
        logger.info { "contact-detail sync start account=${account.name}" }

        // 1a. Labels: fetch + reconcile ContactsContract.Groups before any
        //     contact write so per-contact GroupMembership rows have a
        //     valid local Groups._ID to point at. Failure is non-fatal —
        //     contacts still sync, just without group memberships.
        val labelMap: Map<String, Long> = try {
            val labels = labelsApi.listLabels(LabelType.CONTACT_GROUP).labels
                .map { ProtonLabel(id = it.id, name = it.name.ifBlank { it.id }) }
            reconcileGroups(account, labels)
        } catch (t: Throwable) {
            logger.warn(t) { "labels fetch / reconcile failed; contacts will sync without groups" }
            emptyMap()
        }

        // 1b. Cheap metadata enumeration → ID + ModifyTime + labelIds.
        val metadata = metadataPager.metadata().toList()
        val serverModifyTimes: Map<String, Long> = metadata.associate { it.id to it.modifyTime }
        val serverLabelIds: Map<String, List<String>> =
            metadata.associate { it.id to it.labelIds }
        val serverSourceIds = serverModifyTimes.keys

        // 2. Local state.
        val existing = readExisting(account)
        val storedMappings: Map<String, ContactMapEntity> = contactMapDao.listLive()
            .associateBy { it.protonContactId }

        // 3. Per-ID: cheap-skip (modifyTime) → fetch → decrypt → project →
        //    hash-skip (content_hash) → target list.
        val target = ArrayList<ContactRow>(serverSourceIds.size)
        val perContactMeta = HashMap<String, PerContactMeta>(serverSourceIds.size)
        val now = clock()
        var fetchFailures = 0
        var modifyTimeSkips = 0

        for ((sourceId, serverModifyTime) in serverModifyTimes) {
            val stored = storedMappings[sourceId]
            if (stored != null && stored.modifyTime >= serverModifyTime && serverModifyTime > 0L) {
                // Cheap-skip: server says unchanged. Just refresh bookkeeping.
                modifyTimeSkips += 1
                contactMapDao.upsert(stored.copy(lastSyncedAt = now))
                continue
            }

            val response = try {
                contactsApi.getContact(sourceId)
            } catch (t: Throwable) {
                fetchFailures += 1
                logger.error(t) { "failed to fetch contact (id hash-redacted); skipping this run" }
                continue
            }
            val decrypted = processor.process(response.contact)
            val baseRow = DecryptedContactToRow.convert(decrypted)
            if (baseRow == null) {
                logger.warn { "contact yielded no row (no email); skipping" }
                continue
            }
            // Attach the contact's group memberships (translating Proton
            // LabelIDs → local Groups._ID via the reconciled labelMap).
            val groupRowIds = serverLabelIds[sourceId].orEmpty()
                .mapNotNull { labelId -> labelMap[labelId] }
            val row = if (groupRowIds.isEmpty()) baseRow else baseRow.copy(groupRowIds = groupRowIds)

            val newHash = EmailSyncHash.compute(row)
            val meta = PerContactMeta(
                modifyTime = response.contact.modifyTime,
                verified = decrypted.verified,
                protonUid = decrypted.protonUid,
                hash = newHash
            )
            perContactMeta[sourceId] = meta

            if (stored?.contentHash == newHash) {
                // ModifyTime bumped but visible content unchanged. Refresh
                // bookkeeping; no ContactsContract write needed.
                contactMapDao.upsert(
                    stored.copy(
                        modifyTime = meta.modifyTime,
                        isVerified = meta.verified,
                        protonUid = meta.protonUid,
                        syncStatus = ContactMapEntity.Status.CLEAN,
                        lastError = null,
                        lastSyncedAt = now
                    )
                )
                continue
            }

            target += row
        }

        // 4. Decide intents.
        val intents = RawContactDiffer.diff(
            target = target,
            existing = existing,
            serverSourceIds = serverSourceIds
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
                unverifiedCount = unverified
            )
        }

        // 5. Apply ops.
        val applyResult = applyIntents(account, intents)

        // 6. Reconcile mapping rows.
        val freshExisting = readExisting(account)
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
            unverifiedCount = unverified
        )
    }

    private data class PerContactMeta(
        val modifyTime: Long,
        val verified: Boolean,
        val protonUid: String?,
        val hash: String
    )
}
