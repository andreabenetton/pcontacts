// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.storage.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Per-account high-water marks for sync. `account_name` is the
 * AccountManager account name (the UID we registered post-SRP).
 * `last_known_total` is a sanity check — if the server suddenly reports
 * 0 contacts when we know about 142, the sync engine refuses to
 * wholesale-delete and surfaces a warning instead.
 */
@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey @ColumnInfo(name = "account_name") val accountName: String,
    @ColumnInfo(name = "last_full_sync_at") val lastFullSyncAt: Long,
    @ColumnInfo(name = "last_incremental_sync_at") val lastIncrementalSyncAt: Long,
    @ColumnInfo(name = "last_known_total") val lastKnownTotal: Int
)
