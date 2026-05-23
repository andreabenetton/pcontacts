// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.storage.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.pcontacts.core.storage.db.entity.GroupMapEntity

@Dao
interface GroupMapDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: GroupMapEntity)

    @Query("SELECT * FROM group_map WHERE proton_label_id = :id")
    suspend fun findByLabelId(id: String): GroupMapEntity?

    @Query("SELECT * FROM group_map")
    suspend fun listAll(): List<GroupMapEntity>

    @Query("DELETE FROM group_map WHERE proton_label_id = :id")
    suspend fun deleteByLabelId(id: String)
}
