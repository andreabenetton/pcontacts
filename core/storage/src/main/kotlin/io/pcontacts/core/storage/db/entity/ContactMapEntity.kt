// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.storage.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Per ADR-0008 — the authoritative ProtonID ↔ RawContactID mapping plus
 * the metadata the sync engine needs to answer "what changed since last
 * sync". Holds no decrypted contact content (ADR-0007); only IDs,
 * timestamps, and a content hash.
 *
 * The secondary indices are required by the sync engine's hot lookups:
 *   - `android_raw_contact_id`: ContactsContract observer → which Proton
 *     row does this RawContact belong to?
 *   - `proton_uid`: vCard UID dedup across re-imports.
 */
@Entity(
    tableName = "contact_map",
    indices = [
        Index("android_raw_contact_id"),
        Index("proton_uid")
    ]
)
data class ContactMapEntity(
    @PrimaryKey @ColumnInfo(name = "proton_contact_id") val protonContactId: String,
    @ColumnInfo(name = "proton_uid") val protonUid: String?,
    @ColumnInfo(name = "android_raw_contact_id") val androidRawContactId: Long,
    @ColumnInfo(name = "modify_time") val modifyTime: Long,
    @ColumnInfo(name = "content_hash") val contentHash: String,
    @ColumnInfo(name = "is_verified") val isVerified: Boolean,
    @ColumnInfo(name = "deleted") val deleted: Boolean,
    @ColumnInfo(name = "sync_status") val syncStatus: Int,
    @ColumnInfo(name = "last_error") val lastError: String?,
    @ColumnInfo(name = "last_synced_at") val lastSyncedAt: Long,
    @ColumnInfo(name = "last_known_server_payload_hash", defaultValue = "NULL")
    val lastKnownServerPayloadHash: String? = null
) {
    /**
     * `sync_status` is stored as an Int rather than an enum so future
     * schema migrations don't have to coordinate enum renames across
     * Room's TypeConverters. Values are stable contract.
     */
    object Status {
        const val CLEAN = 0
        const val PENDING_PULL = 1
        const val PENDING_PUSH = 2
        const val CONFLICT = 3
        const val ERROR = 4
    }
}
