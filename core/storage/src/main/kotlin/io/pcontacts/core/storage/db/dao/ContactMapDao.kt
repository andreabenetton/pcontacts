// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.storage.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.pcontacts.core.storage.db.entity.ContactMapEntity

/**
 * Hot-path queries for the sync engine. `upsert` uses REPLACE so a
 * full-sync rewrite never has to do "delete + insert" two-step. Soft
 * delete (`markDeleted`) keeps a tombstone row so a second sync run
 * doesn't reinstate a server-deleted contact.
 */
// Each query is a separate Room-generated SQL hook; collapsing into fewer
// methods loses type-safe parameters.
@Dao
@Suppress("TooManyFunctions")
interface ContactMapDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: ContactMapEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<ContactMapEntity>)

    @Query("SELECT * FROM contact_map WHERE proton_contact_id = :id")
    suspend fun findByProtonId(id: String): ContactMapEntity?

    @Query("SELECT * FROM contact_map WHERE android_raw_contact_id = :rawId")
    suspend fun findByRawContactId(rawId: Long): ContactMapEntity?

    @Query("SELECT * FROM contact_map WHERE proton_uid = :uid LIMIT 1")
    suspend fun findByProtonUid(uid: String): ContactMapEntity?

    @Query("SELECT proton_contact_id FROM contact_map WHERE deleted = 0")
    suspend fun listLiveProtonIds(): List<String>

    @Query("SELECT * FROM contact_map WHERE deleted = 0")
    suspend fun listLive(): List<ContactMapEntity>

    @Query("UPDATE contact_map SET deleted = 1 WHERE proton_contact_id = :id")
    suspend fun markDeleted(id: String)

    @Query("DELETE FROM contact_map WHERE proton_contact_id = :id")
    suspend fun deleteByProtonId(id: String)

    @Query("SELECT COUNT(*) FROM contact_map WHERE deleted = 0")
    suspend fun countLive(): Int

    @Query("SELECT COUNT(*) FROM contact_map WHERE deleted = 0 AND is_verified = 0")
    suspend fun countUnverified(): Int

    @Query("SELECT * FROM contact_map WHERE deleted = 0 AND is_verified = 0")
    suspend fun listUnverified(): List<ContactMapEntity>

    @Query("SELECT MAX(last_synced_at) FROM contact_map WHERE deleted = 0")
    suspend fun maxLastSyncedAt(): Long?

    @Query("SELECT * FROM contact_map WHERE sync_status = 3 AND deleted = 0")
    suspend fun listConflicts(): List<ContactMapEntity>

    @Query("UPDATE contact_map SET sync_status = 0, last_error = NULL WHERE proton_contact_id = :id")
    suspend fun resolveConflict(id: String)

    @Query("DELETE FROM contact_map")
    suspend fun deleteAll()
}
