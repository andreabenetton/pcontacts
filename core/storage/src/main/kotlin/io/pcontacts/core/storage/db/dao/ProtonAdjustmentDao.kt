// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.storage.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.pcontacts.core.storage.db.entity.ProtonAdjustmentEntity

@Dao
interface ProtonAdjustmentDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(adjustments: List<ProtonAdjustmentEntity>)

    @Query("SELECT * FROM proton_adjustment ORDER BY created_at DESC")
    suspend fun listAll(): List<ProtonAdjustmentEntity>

    @Query("DELETE FROM proton_adjustment WHERE proton_contact_id = :protonContactId AND kind = :kind")
    suspend fun delete(protonContactId: String, kind: String)
}
