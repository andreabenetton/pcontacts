// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.storage.db

import android.content.Context
import androidx.room.Room

/**
 * Single composition root for the Room database. Mirrors the role
 * `AuthBootstrap` plays for the SRP login chain: callers in `:core:sync`
 * and `:app` hit one function rather than poking Room's builder directly.
 */
object DatabaseFactory {

    fun create(context: Context): PcontactsDatabase =
        Room.databaseBuilder(
            context.applicationContext,
            PcontactsDatabase::class.java,
            PcontactsDatabase.DATABASE_NAME
        )
            .addMigrations(PcontactsDatabase.MIGRATION_1_2)
            // No fallbackToDestructiveMigration — we ship explicit Migration
            // objects per ADR-0008. A missing migration is a build-time bug,
            // not a "wipe the user's data" event.
            .build()

    /**
     * In-memory variant for Robolectric unit tests. Lives here (not in a
     * test-source-only helper) so all three callers — main code, unit
     * tests, and any future :core:sync integration tests — can share one
     * factory surface.
     */
    fun createInMemory(context: Context): PcontactsDatabase =
        Room.inMemoryDatabaseBuilder(
            context.applicationContext,
            PcontactsDatabase::class.java
        )
            .addMigrations(PcontactsDatabase.MIGRATION_1_2)
            .build()
}
