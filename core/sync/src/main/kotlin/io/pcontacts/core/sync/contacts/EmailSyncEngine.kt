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
import io.pcontacts.core.storage.db.dao.ContactMapDao
import io.pcontacts.core.storage.db.entity.ContactMapEntity
import kotlinx.coroutines.flow.toList

/**
 * Email-only sync engine (plan §17 task 16). Walks
 * `contacts/v4/contacts/emails`, reduces each contact to one
 * ContactRow (name + primary email), and converges
 * ContactsContract + the Room mapping store with the minimum number
 * of writes.
 *
 * The IO seams — `readExisting`, `applyIntents` — are passed as
 * lambdas so the engine stays pure-JVM unit-testable. Production
 * wiring lives in :app's SyncBootstrap, which threads in a
 * ContentProviderClient-backed RawContactReader + BatchApplier.
 *
 * Idempotency: a second `sync()` call with identical server state
 * produces zero ContactsContract writes — the per-row content hash in
 * `contact_map.content_hash` is compared before any intent is emitted.
 */
class EmailSyncEngine(
    private val pager: ContactEmailsPager,
    private val contactMapDao: ContactMapDao,
    private val readExisting: suspend (Account) -> Map<String, Long>,
    private val applyIntents: suspend (Account, List<RawContactOpIntent>) -> ApplyResult,
    private val clock: () -> Long = System::currentTimeMillis,
    private val logger: Logger = RedactingLogger(tag = "EmailSync", sink = NoOpSink)
) {

    suspend fun sync(account: Account): SyncReport {
        logger.info { "email sync start account=${account.name}" }

        // 1. Walk every page and reduce to one ContactRow per Proton contact.
        val allEmails = pager.emails().toList()
        val byContactId = EmailPageReducer.reduce(allEmails)
        val serverSourceIds = byContactId.keys

        // 2. Read the local SOURCE_ID → RawContacts._ID map and the stored
        //    per-contact content hashes.
        val existing = readExisting(account)
        val storedHashes: Map<String, String> = contactMapDao.listLive()
            .associate { it.protonContactId to it.contentHash }

        // 3. Target = rows whose hash changed (or which we've never written).
        //    Caller pre-filtering keeps RawContactDiffer purely structural.
        val target = byContactId.values.filter { row ->
            EmailSyncHash.compute(row) != storedHashes[row.sourceId]
        }

        // 4. Decide intents.
        val intents = RawContactDiffer.diff(
            target = target,
            existing = existing,
            serverSourceIds = serverSourceIds
        )

        if (intents.isEmpty()) {
            logger.info { "email sync done — no changes (server=${serverSourceIds.size})" }
            return SyncReport(
                totalServer = serverSourceIds.size,
                inserted = 0,
                updated = 0,
                deleted = 0,
                unchanged = serverSourceIds.size
            )
        }

        // 5. Apply to ContactsContract.
        val applyResult = applyIntents(account, intents)

        // 6. Reconcile the Room mapping. ContactsContract is the source of
        //    truth for the newly-assigned RawContacts._IDs; re-read after
        //    apply so creates pick up their fresh IDs.
        val freshExisting = readExisting(account)
        val now = clock()
        for (row in target) {
            val rawId = freshExisting[row.sourceId]
            if (rawId == null) {
                // Apply succeeded but the contact didn't materialise in the
                // post-apply read. Could only happen if a concurrent process
                // deleted it; log and skip rather than upsert with a bogus id.
                logger.warn { "post-apply read missed sourceId=${row.sourceId}; skipping mapping" }
                continue
            }
            contactMapDao.upsert(toMappingRow(row, rawId, now))
        }
        for (intent in intents.filterIsInstance<RawContactOpIntent.DeleteContact>()) {
            contactMapDao.deleteByProtonId(intent.sourceId)
        }

        val deletedCount = intents.count { it is RawContactOpIntent.DeleteContact }
        val unchanged = (serverSourceIds.size - target.size).coerceAtLeast(0)
        logger.info {
            "email sync done — inserted=${applyResult.insertedContacts} " +
                "updated=${applyResult.updatedContacts} deleted=$deletedCount unchanged=$unchanged"
        }
        return SyncReport(
            totalServer = serverSourceIds.size,
            inserted = applyResult.insertedContacts,
            updated = applyResult.updatedContacts,
            deleted = deletedCount,
            unchanged = unchanged
        )
    }

    private fun toMappingRow(row: ContactRow, rawId: Long, now: Long) = ContactMapEntity(
        protonContactId = row.sourceId,
        protonUid = null,         // EmailSyncEngine doesn't decrypt; populated by ContactDetailSyncEngine.
        androidRawContactId = rawId,
        modifyTime = 0L,          // EmailSyncEngine has no metadata; populated by ContactDetailSyncEngine.
        contentHash = EmailSyncHash.compute(row),
        isVerified = false,       // No signature verification in the pre-decrypt path.
        deleted = false,
        syncStatus = ContactMapEntity.Status.CLEAN,
        lastError = null,
        lastSyncedAt = now
    )
}
