// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.sync.contacts.convergence

import io.pcontacts.core.storage.db.dao.ContactMapDao
import io.pcontacts.core.storage.db.dao.OutboxDao
import io.pcontacts.core.storage.db.dao.OutboxEnqueue
import io.pcontacts.core.storage.db.entity.ContactMapEntity
import io.pcontacts.core.storage.db.entity.OutboxEntity

/** Every outbox write is a point where the run can stop or the process die; [enqueue] is one transaction. */
// One override per mutating DAO query; the count follows the DAO.
@Suppress("TooManyFunctions")
internal class FaultyOutboxDao(
    private val inner: OutboxDao,
    private val faults: Faults
) : OutboxDao by inner {
    private suspend fun <T> room(label: String, write: suspend () -> T): T = faults.checkpoint(Site.ROOM, label, write)

    override suspend fun insert(entry: OutboxEntity): Long = room("outbox insert") { inner.insert(entry) }

    override suspend fun enqueue(contactId: String, opType: Int, payloadHash: String, now: Long): OutboxEnqueue =
        room("outbox enqueue $contactId") { inner.enqueue(contactId, opType, payloadHash, now) }

    override suspend fun replaceLive(id: Long, opType: Int, payloadHash: String, createdAt: Long) =
        room("outbox replace") { inner.replaceLive(id, opType, payloadHash, createdAt) }

    override suspend fun deleteIfUnchanged(id: Long, opType: Int, payloadHash: String) =
        room("outbox done $id") { inner.deleteIfUnchanged(id, opType, payloadHash) }

    override suspend fun recordFailure(id: Long, attempts: Int, error: String?, nextAt: Long) =
        room("outbox failure $id") { inner.recordFailure(id, attempts, error, nextAt) }

    override suspend fun quarantine(id: Long, error: String?) =
        room("outbox quarantine $id") { inner.quarantine(id, error) }

    override suspend fun requeue(id: Long) = room("outbox requeue $id") { inner.requeue(id) }

    override suspend fun deleteById(id: Long) = room("outbox delete $id") { inner.deleteById(id) }

    override suspend fun deleteByContact(contactId: String) =
        room("outbox delete $contactId") { inner.deleteByContact(contactId) }

    override suspend fun deleteAll() = room("outbox clear") { inner.deleteAll() }
}

/** Every mapping write is a point where the run can stop or the process die. */
// One override per mutating DAO query; the count follows the DAO.
@Suppress("TooManyFunctions")
internal class FaultyContactMapDao(
    private val inner: ContactMapDao,
    private val faults: Faults
) : ContactMapDao by inner {
    private suspend fun <T> room(label: String, write: suspend () -> T): T = faults.checkpoint(Site.ROOM, label, write)

    override suspend fun upsert(entry: ContactMapEntity) =
        room("map upsert ${entry.protonContactId}") { inner.upsert(entry) }

    override suspend fun upsertAll(entries: List<ContactMapEntity>) =
        room("map upsert all") { inner.upsertAll(entries) }

    override suspend fun markDeleted(id: String) = room("map deleted $id") { inner.markDeleted(id) }

    override suspend fun resolveConflict(id: String) = room("map resolve $id") { inner.resolveConflict(id) }

    override suspend fun deleteByProtonId(id: String) = room("map delete $id") { inner.deleteByProtonId(id) }

    override suspend fun deleteByProtonIds(ids: List<String>) =
        room("map delete $ids") { inner.deleteByProtonIds(ids) }

    override suspend fun setMergeBase(id: String, sealed: ByteArray?) =
        room("map base $id") { inner.setMergeBase(id, sealed) }

    override suspend fun setServerLabelIds(id: String, labelIds: String?) =
        room("map labels $id") { inner.setServerLabelIds(id, labelIds) }

    override suspend fun markClean(id: String, now: Long) = room("map clean $id") { inner.markClean(id, now) }

    override suspend fun markConflict(id: String, error: String) =
        room("map conflict $id") { inner.markConflict(id, error) }

    override suspend fun forceRefetch(id: String) = room("map refetch $id") { inner.forceRefetch(id) }

    override suspend fun deleteAll() = room("map clear") { inner.deleteAll() }
}
