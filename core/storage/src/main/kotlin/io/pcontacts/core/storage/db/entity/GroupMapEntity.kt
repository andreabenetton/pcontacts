// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.storage.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Proton LabelID ↔ Android Group._ID mapping. Populated by
 * LocalGroupsWriter during contact-detail sync.
 */
@Entity(tableName = "group_map")
data class GroupMapEntity(
    @PrimaryKey @ColumnInfo(name = "proton_label_id") val protonLabelId: String,
    @ColumnInfo(name = "android_group_id") val androidGroupId: Long,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "modify_time") val modifyTime: Long
)
