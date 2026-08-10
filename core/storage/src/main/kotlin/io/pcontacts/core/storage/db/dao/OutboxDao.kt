// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.storage.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import io.pcontacts.core.storage.db.entity.OutboxEntity

/**
 * Queries for the persistent outbox (ADR-0017 §5B). The write engine
 * drains [listReady] on each sync run; failed entries advance via
 * [recordFailure] or get permanently side-lined via [quarantine].
 */
@Dao
interface OutboxDao {

    @Insert
    suspend fun insert(entry: OutboxEntity): Long

    @Query("SELECT * FROM outbox WHERE quarantined = 0 AND next_attempt_at <= :now ORDER BY created_at")
    suspend fun listReady(now: Long): List<OutboxEntity>

    @Query("SELECT * FROM outbox WHERE proton_contact_id = :contactId")
    suspend fun findByContact(contactId: String): List<OutboxEntity>

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
