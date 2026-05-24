// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.storage.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.pcontacts.core.storage.db.dao.ContactMapDao
import io.pcontacts.core.storage.db.dao.GroupMapDao
import io.pcontacts.core.storage.db.dao.OutboxDao
import io.pcontacts.core.storage.db.dao.SyncStateDao
import io.pcontacts.core.storage.db.entity.ContactMapEntity
import io.pcontacts.core.storage.db.entity.GroupMapEntity
import io.pcontacts.core.storage.db.entity.OutboxEntity
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
        SyncStateEntity::class,
        OutboxEntity::class
    ],
    version = 2,
    exportSchema = true
)
abstract class PcontactsDatabase : RoomDatabase() {
    abstract fun contactMapDao(): ContactMapDao
    abstract fun groupMapDao(): GroupMapDao
    abstract fun syncStateDao(): SyncStateDao
    abstract fun outboxDao(): OutboxDao

    companion object {
        const val DATABASE_NAME = "pcontacts.db"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE contact_map ADD COLUMN last_known_server_payload_hash TEXT DEFAULT NULL"
                )
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS outbox (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        proton_contact_id TEXT NOT NULL,
                        op_type INTEGER NOT NULL,
                        payload_hash TEXT NOT NULL,
                        attempts INTEGER NOT NULL DEFAULT 0,
                        last_error TEXT,
                        next_attempt_at INTEGER NOT NULL DEFAULT 0,
                        created_at INTEGER NOT NULL,
                        quarantined INTEGER NOT NULL DEFAULT 0
                    )"""
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_outbox_proton_contact_id ON outbox(proton_contact_id)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_outbox_next_attempt_at ON outbox(next_attempt_at)"
                )
            }
        }
    }
}
