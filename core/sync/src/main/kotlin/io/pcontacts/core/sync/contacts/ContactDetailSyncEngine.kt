// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.sync.contacts

import android.accounts.Account
import io.pcontacts.core.contactswriter.ApplyResult
import io.pcontacts.core.contactswriter.ContactRow
import io.pcontacts.core.contactswriter.RawContactDiffer
import io.pcontacts.core.contactswriter.RawContactOpIntent
import io.pcontacts.core.logging.Logger
import io.pcontacts.core.logging.NoOpSink
import io.pcontacts.core.logging.RedactingLogger
import io.pcontacts.core.proton.api.contacts.ContactEmailsPager
import io.pcontacts.core.proton.api.contacts.ProtonContactsApi
import io.pcontacts.core.protoncontacts.ContactProcessor
import io.pcontacts.core.storage.db.dao.ContactMapDao
import io.pcontacts.core.storage.db.entity.ContactMapEntity
import kotlinx.coroutines.flow.toList

/**
 * Full-decrypt sync engine (plan §17 task 17, end-to-end). Per-contact
 * flow:
 *   1. Enumerate ContactIDs server-side via the email pager.
 *   2. For each ID, GET /contacts/v4/contacts/{id} → full Cards[].
 *   3. ContactProcessor decrypts + merges Cards → DecryptedContact.
 *   4. DecryptedContactToRow projects to the MVP ContactRow shape.
 *   5. Hash-compare against the stored content_hash; skip writes when
 *      unchanged. Mapping rows still get a `last_synced_at` /
 *      `is_verified` / `proton_uid` refresh.
 *   6. RawContactDiffer + applier produce + apply ContactsContract ops.
 *   7. Reconcile the Room mapping with the post-apply RawContacts._IDs.
 *
 * Sibling to EmailSyncEngine — same IO seams, same idempotency
 * contract. Differs in that the displayName written to
 * ContactsContract comes from the decrypted SIGNED card's FN rather
 * than the email row's denormalised Name, and the per-contact
 * `is_verified` flag reflects whether every signed card verified.
 *
 * Per-contact fetch is unconditional in MVP — there's no metadata
 * listing endpoint here yet, so we can't cheap-skip by ModifyTime
 * before fetching. The content_hash check still avoids redundant
 * ContactsContract writes; the network cost is the open follow-up.
 */
class ContactDetailSyncEngine(
    private val pager: ContactEmailsPager,
    private val contactsApi: ProtonContactsApi,
    private val processor: ContactProcessor,
    private val contactMapDao: ContactMapDao,
    private val readExisting: suspend (Account) -> Map<String, Long>,
    private val applyIntents: suspend (Account, List<RawContactOpIntent>) -> ApplyResult,
    private val clock: () -> Long = System::currentTimeMillis,
    private val logger: Logger = RedactingLogger(tag = "ContactDetailSync", sink = NoOpSink)
) {

    suspend fun sync(account: Account): SyncReport {
        logger.info { "contact-detail sync start account=${account.name}" }

        // 1. Enumerate server-side ContactIDs (cheapest: one /emails listing).
        val emails = pager.emails().toList()
        val serverSourceIds = emails.map { it.contactId }.toSet()

        // 2. Local state.
        val existing = readExisting(account)
        val storedMappings: Map<String, ContactMapEntity> = contactMapDao.listLive()
            .associateBy { it.protonContactId }

        // 3. For each server ID, fetch + decrypt + project. Track per-id
        //    metadata (modify_time, verified, proton_uid) so the post-apply
        //    mapping refresh has everything it needs.
        val target = ArrayList<ContactRow>(serverSourceIds.size)
        val perContactMeta = HashMap<String, PerContactMeta>(serverSourceIds.size)
        val now = clock()
        var fetchFailures = 0

        for (sourceId in serverSourceIds) {
            val response = try {
                contactsApi.getContact(sourceId)
            } catch (t: Throwable) {
                fetchFailures += 1
                logger.error(t) { "failed to fetch contact (id hash-redacted); skipping this run" }
                continue
            }
            val decrypted = processor.process(response.contact)
            val row = DecryptedContactToRow.convert(decrypted)
            if (row == null) {
                logger.warn { "contact yielded no row (no email); skipping" }
                continue
            }

            val newHash = EmailSyncHash.compute(row)
            val meta = PerContactMeta(
                modifyTime = response.contact.modifyTime,
                verified = decrypted.verified,
                protonUid = decrypted.protonUid,
                hash = newHash
            )
            perContactMeta[sourceId] = meta

            val storedHash = storedMappings[sourceId]?.contentHash
            if (storedHash == newHash) {
                // No ContactsContract write needed; refresh the mapping
                // bookkeeping (modify_time, verified, last_synced_at).
                storedMappings[sourceId]?.let { existingMap ->
                    contactMapDao.upsert(
                        existingMap.copy(
                            modifyTime = meta.modifyTime,
                            isVerified = meta.verified,
                            protonUid = meta.protonUid,
                            syncStatus = ContactMapEntity.Status.CLEAN,
                            lastError = null,
                            lastSyncedAt = now
                        )
                    )
                }
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
            val unchanged = serverSourceIds.size - target.size - fetchFailures
            logger.info {
                "contact-detail sync done — no ContactsContract writes; " +
                    "unchanged=$unchanged fetchFailures=$fetchFailures"
            }
            return SyncReport(
                totalServer = serverSourceIds.size,
                inserted = 0,
                updated = 0,
                deleted = 0,
                unchanged = unchanged.coerceAtLeast(0)
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
        logger.info {
            "contact-detail sync done — inserted=${applyResult.insertedContacts} " +
                "updated=${applyResult.updatedContacts} deleted=$deletedCount " +
                "unchanged=$unchanged fetchFailures=$fetchFailures"
        }
        return SyncReport(
            totalServer = serverSourceIds.size,
            inserted = applyResult.insertedContacts,
            updated = applyResult.updatedContacts,
            deleted = deletedCount,
            unchanged = unchanged
        )
    }

    private data class PerContactMeta(
        val modifyTime: Long,
        val verified: Boolean,
        val protonUid: String?,
        val hash: String
    )
}
