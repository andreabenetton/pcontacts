// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.storage.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
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

    companion object {
        private const val TEST_DB = "migration-test"
    }
}
