// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.storage.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import io.pcontacts.core.storage.db.entity.OutboxEntity
import io.pcontacts.core.storage.db.entity.OutboxEntity.OpType

/** What [OutboxDao.enqueue] did with the change. */
enum class OutboxEnqueue { INSERTED, REPLACED, UNCHANGED, DROPPED }

/**
 * Queries for the persistent outbox (ADR-0017 §5B). The write engine
 * drains [listReady] on each sync run; failed entries advance via
 * [recordFailure] or get permanently side-lined via [quarantine].
 *
 * [enqueue] is the only way a change enters the queue: it keeps one
 * live row per contact (see [OutboxEntity]).
 */
// Each query is a separate Room-generated SQL hook; collapsing into fewer
// methods loses type-safe parameters.
@Dao
@Suppress("TooManyFunctions")
interface OutboxDao {

    @Insert
    suspend fun insert(entry: OutboxEntity): Long

    @Query("SELECT * FROM outbox WHERE quarantined = 0 AND next_attempt_at <= :now ORDER BY created_at")
    suspend fun listReady(now: Long): List<OutboxEntity>

    @Query("SELECT * FROM outbox WHERE proton_contact_id = :contactId")
    suspend fun findByContact(contactId: String): List<OutboxEntity>

    @Query("SELECT * FROM outbox WHERE proton_contact_id = :contactId AND quarantined = 0 LIMIT 1")
    suspend fun findLive(contactId: String): OutboxEntity?

    @Query(
        "UPDATE outbox SET op_type = :opType, payload_hash = :payloadHash, attempts = 0, last_error = NULL, " +
            "next_attempt_at = 0, created_at = :createdAt WHERE id = :id"
    )
    suspend fun replaceLive(id: Long, opType: Int, payloadHash: String, createdAt: Long)

    /** Removes the row only if it still carries the operation and hash that were pushed. */
    @Query("DELETE FROM outbox WHERE id = :id AND op_type = :opType AND payload_hash = :payloadHash")
    suspend fun deleteIfUnchanged(id: Long, opType: Int, payloadHash: String)

    /**
     * Coalesces a change into the contact's live row, atomically:
     * a DELETE over an unpushed CREATE drops the row (Proton never saw
     * the contact); a DELETE over anything else becomes a DELETE whose
     * grace period starts now; any edit over a DELETE cancels the
     * delete; an edit over a CREATE stays a CREATE with the new hash;
     * FORCE_UPDATE wins over UPDATE either way; the same operation with
     * the same hash leaves the row and its backoff untouched.
     */
    @Transaction
    suspend fun enqueue(contactId: String, opType: Int, payloadHash: String, now: Long): OutboxEnqueue {
        val live = findLive(contactId)
        if (live == null) {
            insert(
                OutboxEntity(
                    protonContactId = contactId,
                    opType = opType,
                    payloadHash = payloadHash,
                    createdAt = now
                )
            )
            return OutboxEnqueue.INSERTED
        }
        if (opType == OpType.DELETE && live.opType == OpType.CREATE) {
            deleteById(live.id)
            return OutboxEnqueue.DROPPED
        }
        val newOp = coalescedOp(live.opType, opType)
        val newHash = if (newOp == OpType.UPDATE || newOp == OpType.CREATE) payloadHash else ""
        if (newOp == live.opType && newHash == live.payloadHash) return OutboxEnqueue.UNCHANGED
        val createdAt = if (newOp == OpType.DELETE) now else live.createdAt
        replaceLive(live.id, newOp, newHash, createdAt)
        return OutboxEnqueue.REPLACED
    }

    @Query(
        "UPDATE outbox SET attempts = :attempts, last_error = :error, next_attempt_at = :nextAt " +
            "WHERE id = :id"
    )
    suspend fun recordFailure(id: Long, attempts: Int, error: String?, nextAt: Long)

    @Query("UPDATE outbox SET quarantined = 1, last_error = :error WHERE id = :id")
    suspend fun quarantine(id: Long, error: String?)

    @Query("SELECT * FROM outbox WHERE quarantined = 1 ORDER BY created_at")
    suspend fun listQuarantined(): List<OutboxEntity>

    /**
     * Returns a quarantined entry to the live queue: clears the
     * quarantine flag, resets the backoff state so the next [listReady]
     * picks it up immediately, and drops the stale failure reason.
     */
    @Query(
        "UPDATE outbox SET quarantined = 0, attempts = 0, last_error = NULL, next_attempt_at = 0 " +
            "WHERE id = :id AND quarantined = 1"
    )
    suspend fun requeue(id: Long)

    @Query("DELETE FROM outbox WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM outbox WHERE proton_contact_id = :contactId")
    suspend fun deleteByContact(contactId: String)

    @Query("DELETE FROM outbox")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM outbox WHERE quarantined = 0")
    suspend fun countPending(): Int

    @Query("SELECT COUNT(*) FROM outbox WHERE quarantined = 1")
    suspend fun countQuarantined(): Int

    @Query("SELECT * FROM outbox WHERE op_type = 2 AND quarantined = 0")
    suspend fun listPendingDeletes(): List<OutboxEntity>
}

/** The operation a live row ends up with when [incoming] arrives on top of [live] (see [OutboxDao.enqueue]). */
internal fun coalescedOp(live: Int, incoming: Int): Int = when {
    incoming == OpType.DELETE -> OpType.DELETE
    live == OpType.DELETE -> incoming
    live == OpType.CREATE -> OpType.CREATE
    live == OpType.FORCE_UPDATE || incoming == OpType.FORCE_UPDATE -> OpType.FORCE_UPDATE
    else -> incoming
}
