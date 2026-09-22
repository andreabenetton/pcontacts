// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.sync.contacts

import io.pcontacts.core.storage.db.dao.ContactMapDao
import io.pcontacts.core.storage.db.dao.OutboxDao
import io.pcontacts.core.storage.db.entity.OutboxEntity

/**
 * Undoes a deletion the user cancelled before it reached Proton
 * (ADR-0022): the queued DELETE goes, and the contact comes back
 * locally — the tombstoned RawContact is made live again when the
 * provider still holds it (its Data rows were never touched), otherwise
 * the mapping is marked for a refetch so the next pull recreates it.
 * Returns true when the row was restored in place.
 */
internal suspend fun cancelPendingDelete(
    contactMapDao: ContactMapDao,
    outboxDao: OutboxDao,
    protonContactId: String,
    restoreTombstones: suspend (protonContactId: String) -> Int
): Boolean {
    outboxDao.findLive(protonContactId)
        ?.takeIf { it.opType == OutboxEntity.OpType.DELETE }
        ?.let { outboxDao.deleteById(it.id) }
    val restored = restoreTombstones(protonContactId)
    if (restored == 0) contactMapDao.forceRefetch(protonContactId)
    return restored > 0
}
