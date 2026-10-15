// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.storage.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * A value of a contact that was conformed to the rules of Proton's editor before it was sent
 * (ADR-0017, 2026-10-15, third amendment), kept until the user dismisses it in Settings. One row
 * per contact and [kind]; it holds the kind of adjustment only, never the value (ADR-0007).
 */
@Entity(tableName = "proton_adjustment", primaryKeys = ["proton_contact_id", "kind"])
data class ProtonAdjustmentEntity(
    @ColumnInfo(name = "proton_contact_id") val protonContactId: String,
    @ColumnInfo(name = "kind") val kind: String,
    @ColumnInfo(name = "created_at") val createdAt: Long
)
