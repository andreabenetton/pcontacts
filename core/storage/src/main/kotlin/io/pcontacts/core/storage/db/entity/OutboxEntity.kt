// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.storage.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Persistent outbox for outbound contact mutations (ADR-0017 §5B, as
 * amended). There is exactly **one live (non-quarantined) row per
 * `proton_contact_id`**: `OutboxDao.enqueue` coalesces every new
 * change into the existing row instead of appending, so two edits
 * between pushes never become two requests and two CREATEs for the
 * same local contact cannot race.
 *
 * Rows are quarantined (not retried) after a permanent failure (4xx
 * except 429). Transient failures (5xx, 429, IOException) increment
 * [attempts] and push [nextAttemptAt] forward with exponential backoff.
 *
 * The outbox stores no decrypted contact content (ADR-0007).
 * [payloadHash] is the hash of the local row at the time it was last
 * enqueued: the dedup key at enqueue time and the optimistic token when
 * a push completes (the row is removed only if it still carries the
 * hash that was pushed). It is `""` for DELETE and FORCE_UPDATE, whose
 * content is not compared.
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

        /** The user chose "use phone version" for a conflict: push local as-is, no merge. */
        const val FORCE_UPDATE = 3
    }
}
