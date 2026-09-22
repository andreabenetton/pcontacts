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
 * The single Room database for pcontacts. Holds mapping + sync
 * metadata (ADR-0008) and, sealed under the Keystore KEK, the per-contact
 * merge base (ADR-0017 §3 / ADR-0018) — never plaintext contact content,
 * never tokens (ADR-0009 keeps those in the Keystore-sealed secret store).
 *
 * `exportSchema = true` writes the v(N) JSON dump to
 * `:core:storage/schemas/<DB qualified name>/<version>.json`;
 * `MigrationTest` feeds them to `MigrationTestHelper` for every migration.
 */
@Database(
    entities = [
        ContactMapEntity::class,
        GroupMapEntity::class,
        SyncStateEntity::class,
        OutboxEntity::class
    ],
    version = 3,
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

        /**
         * ADR-0017 amendment: the sealed merge base replaces the local
         * hash (which is nulled, never read again), and the outbox keeps
         * one live row per contact — older duplicates go, the newest
         * survives, quarantined rows are untouched.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE contact_map ADD COLUMN last_known_server_payload BLOB DEFAULT NULL")
                db.execSQL("UPDATE contact_map SET last_known_server_payload_hash = NULL")
                db.execSQL(
                    """DELETE FROM outbox WHERE quarantined = 0 AND id NOT IN (
                        SELECT MAX(id) FROM outbox WHERE quarantined = 0 GROUP BY proton_contact_id
                    )"""
                )
            }
        }
    }
}
