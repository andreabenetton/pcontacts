// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.storage.db

import androidx.room.Database
import androidx.room.RoomDatabase
import io.pcontacts.core.storage.db.dao.ContactMapDao
import io.pcontacts.core.storage.db.dao.GroupMapDao
import io.pcontacts.core.storage.db.dao.SyncStateDao
import io.pcontacts.core.storage.db.entity.ContactMapEntity
import io.pcontacts.core.storage.db.entity.GroupMapEntity
import io.pcontacts.core.storage.db.entity.SyncStateEntity

/**
 * The single Room database for pcontacts. Holds only mapping + sync
 * metadata (ADR-0008) — never decrypted contact content (ADR-0007),
 * never tokens (ADR-0009 keeps those in EncryptedSharedPreferences).
 *
 * `exportSchema = true` writes the v(N) JSON dump to
 * `:core:storage/schemas/<DB qualified name>/<version>.json`, the input
 * MigrationTestHelper needs once a v2 migration exists.
 */
@Database(
    entities = [
        ContactMapEntity::class,
        GroupMapEntity::class,
        SyncStateEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class PcontactsDatabase : RoomDatabase() {
    abstract fun contactMapDao(): ContactMapDao
    abstract fun groupMapDao(): GroupMapDao
    abstract fun syncStateDao(): SyncStateDao

    companion object {
        const val DATABASE_NAME = "pcontacts.db"
    }
}
