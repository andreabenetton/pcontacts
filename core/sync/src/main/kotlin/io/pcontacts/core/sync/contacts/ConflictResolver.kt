// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.sync.contacts

import io.pcontacts.core.storage.db.dao.ContactMapDao
import io.pcontacts.core.storage.db.dao.OutboxDao
import io.pcontacts.core.storage.db.entity.ContactMapEntity
import io.pcontacts.core.storage.db.entity.OutboxEntity

/**
 * The mapping's `lastError` when the contact was deleted on Proton while the phone still
 * owned a change to it: the pull keeps the row and the user decides (ADR-0017 §3C).
 */
const val SERVER_DELETED_CONFLICT = "conflict: deleted on Proton"

/**
 * Settles a conflict the user decided (ADR-0017 §3C). "Use phone
 * version" queues a FORCE_UPDATE — the next push sends the local row
 * as-is, without a merge, and re-captures the base from it; when the
 * Proton copy is gone it queues a CREATE instead, so the phone's version
 * becomes a new Proton contact. "Use Proton version" drops whatever is
 * still queued or quarantined for the contact and makes the next pull
 * rewrite the local row from the server (or, when the Proton copy is
 * gone, delete it).
 */
internal suspend fun resolveConflict(
    contactMapDao: ContactMapDao,
    outboxDao: OutboxDao,
    protonContactId: String,
    useLocal: Boolean,
    now: Long
) {
    val mapping = contactMapDao.findByProtonId(protonContactId)
    val serverDeleted = mapping?.lastError == SERVER_DELETED_CONFLICT
    contactMapDao.resolveConflict(protonContactId)
    when {
        useLocal && serverDeleted && mapping != null -> {
            val localId = LOCAL_ID_PREFIX + mapping.androidRawContactId
            outboxDao.deleteByContact(protonContactId)
            contactMapDao.deleteByProtonId(protonContactId)
            contactMapDao.upsert(
                mapping.copy(
                    protonContactId = localId,
                    protonUid = null,
                    modifyTime = 0L,
                    contentHash = "",
                    syncStatus = ContactMapEntity.Status.CLEAN,
                    lastError = null,
                    lastKnownServerPayloadHash = null,
                    lastKnownServerPayload = null
                )
            )
            outboxDao.enqueue(localId, OutboxEntity.OpType.CREATE, "", now)
        }
        useLocal -> outboxDao.enqueue(protonContactId, OutboxEntity.OpType.FORCE_UPDATE, "", now)
        else -> {
            outboxDao.deleteByContact(protonContactId)
            contactMapDao.forceRefetch(protonContactId)
        }
    }
}
