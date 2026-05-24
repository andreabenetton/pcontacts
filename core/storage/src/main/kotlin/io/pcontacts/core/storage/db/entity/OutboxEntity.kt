// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.storage.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Persistent outbox for outbound contact mutations (ADR-0017 §5B).
 * Each row represents a single pending CREATE, UPDATE, or DELETE that
 * the [ContactWriteEngine] will push to the Proton API on the next
 * sync run.
 *
 * Rows are quarantined (not retried) after a permanent failure (4xx
 * except 429). Transient failures (5xx, 429, IOException) increment
 * [attempts] and push [nextAttemptAt] forward with exponential backoff.
 *
 * The outbox stores no decrypted contact content (ADR-0007). The
 * [payloadHash] is used to dedup and coalesce successive edits to
 * the same contact before a push drains the queue.
 */
@Entity(
    tableName = "outbox",
    indices = [
        Index("proton_contact_id"),
        Index("next_attempt_at")
    ]
)
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "proton_contact_id") val protonContactId: String,
    @ColumnInfo(name = "op_type") val opType: Int,
    @ColumnInfo(name = "payload_hash") val payloadHash: String,
    @ColumnInfo(name = "attempts") val attempts: Int = 0,
    @ColumnInfo(name = "last_error") val lastError: String? = null,
    @ColumnInfo(name = "next_attempt_at") val nextAttemptAt: Long = 0L,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "quarantined") val quarantined: Boolean = false
) {
    object OpType {
        const val CREATE = 0
        const val UPDATE = 1
        const val DELETE = 2
    }
}
