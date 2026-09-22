// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.sync.contacts

import io.pcontacts.core.storage.db.dao.ContactMapDao
import io.pcontacts.core.storage.db.dao.OutboxDao
import io.pcontacts.core.storage.db.entity.OutboxEntity

/**
 * Settles a conflict the user decided (ADR-0017 §3C). "Use phone
 * version" queues a FORCE_UPDATE — the next push sends the local row
 * as-is, without a merge, and re-captures the base from it. "Use
 * Proton version" drops whatever is still queued for the contact and
 * makes the next pull rewrite the local row from the server.
 */
internal suspend fun resolveConflict(
    contactMapDao: ContactMapDao,
    outboxDao: OutboxDao,
    protonContactId: String,
    useLocal: Boolean,
    now: Long
) {
    contactMapDao.resolveConflict(protonContactId)
    if (useLocal) {
        outboxDao.enqueue(protonContactId, OutboxEntity.OpType.FORCE_UPDATE, "", now)
    } else {
        outboxDao.findLive(protonContactId)?.let { outboxDao.deleteById(it.id) }
        contactMapDao.forceRefetch(protonContactId)
    }
}
