// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.storage.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        PcontactsDatabase::class.java
    )

    @Test fun migrate_1_to_2_adds_outbox_table_and_server_hash_column() {
        helper.createDatabase(TEST_DB, 1).apply {
            execSQL(
                """INSERT INTO contact_map (
                    proton_contact_id, proton_uid, android_raw_contact_id,
                    modify_time, content_hash, is_verified, deleted,
                    sync_status, last_error, last_synced_at
                ) VALUES (
                    'ct-1', 'uid-1', 100,
                    1700000000, 'hash-v1', 1, 0,
                    0, NULL, 1700000001
                )"""
            )
            close()
        }

        val migratedDb = helper.runMigrationsAndValidate(
            TEST_DB,
            2,
            true,
            PcontactsDatabase.MIGRATION_1_2
        )

        migratedDb.query(
            "SELECT last_known_server_payload_hash FROM contact_map WHERE proton_contact_id = 'ct-1'"
        ).use { cursor ->
            assert(cursor.moveToFirst()) { "contact_map row should survive migration" }
            assert(cursor.isNull(0)) { "last_known_server_payload_hash should default to NULL" }
        }

        migratedDb.query("SELECT name FROM sqlite_master WHERE type='table' AND name='outbox'").use { cursor ->
            assert(cursor.moveToFirst()) { "outbox table should exist after migration" }
        }

        migratedDb.query(
            "SELECT name FROM sqlite_master WHERE type='index' AND name='index_outbox_proton_contact_id'"
        ).use { cursor ->
            assert(cursor.moveToFirst()) { "outbox proton_contact_id index should exist" }
        }

        migratedDb.query(
            "SELECT name FROM sqlite_master WHERE type='index' AND name='index_outbox_next_attempt_at'"
        ).use { cursor ->
            assert(cursor.moveToFirst()) { "outbox next_attempt_at index should exist" }
        }

        migratedDb.execSQL(
            """INSERT INTO outbox (
                proton_contact_id, op_type, payload_hash,
                attempts, last_error, next_attempt_at, created_at, quarantined
            ) VALUES ('ct-1', 1, 'hash-upd', 0, NULL, 0, 1700000000, 0)"""
        )
        migratedDb.query("SELECT proton_contact_id, op_type FROM outbox").use { cursor ->
            assert(cursor.moveToFirst()) { "outbox should accept inserts after migration" }
            assert(cursor.getString(0) == "ct-1")
            assert(cursor.getInt(1) == 1)
        }

        migratedDb.close()
    }

    @Test fun migrate_2_to_3_adds_merge_base_nulls_legacy_hash_and_collapses_duplicate_outbox_rows() {
        helper.createDatabase(TEST_DB, 2).apply {
            execSQL(
                """INSERT INTO contact_map (
                    proton_contact_id, proton_uid, android_raw_contact_id,
                    modify_time, content_hash, is_verified, deleted,
                    sync_status, last_error, last_synced_at, last_known_server_payload_hash
                ) VALUES ('ct-1', 'uid-1', 100, 1700000000, 'hash-v1', 1, 0, 0, NULL, 1700000001, 'legacy-hash')"""
            )
            // ct-1: two live UPDATE rows (ids 1, 2) and a quarantined one (id 3); ct-2: one live DELETE (id 4).
            execSQL(outboxRow("ct-1", opType = 1, hash = "h1", quarantined = 0))
            execSQL(outboxRow("ct-1", opType = 1, hash = "h2", quarantined = 0))
            execSQL(outboxRow("ct-1", opType = 1, hash = "h0", quarantined = 1))
            execSQL(outboxRow("ct-2", opType = 2, hash = "", quarantined = 0))
            close()
        }

        val migratedDb = helper.runMigrationsAndValidate(TEST_DB, 3, true, PcontactsDatabase.MIGRATION_2_3)

        migratedDb.query(
            "SELECT last_known_server_payload, last_known_server_payload_hash FROM contact_map WHERE proton_contact_id = 'ct-1'"
        ).use { cursor ->
            assertTrue("contact_map row should survive migration", cursor.moveToFirst())
            assertTrue("merge base column defaults to NULL", cursor.isNull(0))
            assertTrue("legacy hash is nulled", cursor.isNull(1))
        }

        migratedDb.query("SELECT id, payload_hash FROM outbox WHERE proton_contact_id = 'ct-1' AND quarantined = 0")
            .use { cursor ->
                assertEquals("one live row per contact", 1, cursor.count)
                cursor.moveToFirst()
                assertEquals("the newest live row survives", 2L, cursor.getLong(0))
                assertEquals("h2", cursor.getString(1))
            }
        migratedDb.query("SELECT COUNT(*) FROM outbox WHERE quarantined = 1").use { cursor ->
            cursor.moveToFirst()
            assertEquals("quarantined rows are untouched", 1, cursor.getInt(0))
        }
        migratedDb.query("SELECT COUNT(*) FROM outbox WHERE proton_contact_id = 'ct-2'").use { cursor ->
            cursor.moveToFirst()
            assertEquals("other contacts keep their single row", 1, cursor.getInt(0))
        }

        migratedDb.close()
    }

    private fun outboxRow(contactId: String, opType: Int, hash: String, quarantined: Int): String =
        """INSERT INTO outbox (
            proton_contact_id, op_type, payload_hash, attempts, last_error, next_attempt_at, created_at, quarantined
        ) VALUES ('$contactId', $opType, '$hash', 0, NULL, 0, 1700000000, $quarantined)"""

    companion object {
        private const val TEST_DB = "migration-test"
    }
}
