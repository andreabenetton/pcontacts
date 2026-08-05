// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.app.sync

import android.accounts.Account
import android.app.Application
import android.content.ContentProviderClient
import android.content.SyncResult
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import io.pcontacts.app.account.PROTON_ACCOUNT_TYPE
import io.pcontacts.core.storage.InMemoryUserPreferences
import io.pcontacts.core.sync.contacts.SyncReport
import io.pcontacts.core.sync.contacts.WriteReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ProtonSyncAdapterSettingsInitTest {

    private val account = Account("test@proton.me", PROTON_ACCOUNT_TYPE)
    private val authority = "com.android.contacts"
    private lateinit var provider: ContentProviderClient

    private val emptyWriteReport = WriteReport(
        pushed = 0,
        created = 0,
        updated = 0,
        deleted = 0,
        failed = 0,
        quarantined = 0,
        skippedGrace = 0,
        conflicted = 0
    )
    private val emptySyncReport = SyncReport(
        totalServer = 0,
        inserted = 0,
        updated = 0,
        deleted = 0,
        unchanged = 0
    )

    @Before fun setUp() {
        Robolectric.buildContentProvider(StubProvider::class.java)
            .create("com.android.contacts")
        provider = ApplicationProvider.getApplicationContext<Application>()
            .contentResolver.acquireContentProviderClient("com.android.contacts")!!
    }

    @Test
    fun settings_init_runs_before_the_sync_engines() {
        val order = mutableListOf<String>()
        val adapter = ProtonSyncAdapter(
            ApplicationProvider.getApplicationContext(),
            syncRunner = { _, _, _ ->
                order += "sync"
                emptyWriteReport to emptySyncReport
            },
            userPreferences = InMemoryUserPreferences(),
            settingsInitializer = { _, _, _ ->
                order += "settings"
                true
            }
        )
        adapter.onPerformSync(account, Bundle.EMPTY, authority, provider, SyncResult())
        assertEquals(listOf("settings", "sync"), order)
    }

    @Test
    fun settings_init_failure_does_not_block_the_sync_run() {
        var syncRan = false
        val prefs = InMemoryUserPreferences()
        val adapter = ProtonSyncAdapter(
            ApplicationProvider.getApplicationContext(),
            syncRunner = { _, _, _ ->
                syncRan = true
                emptyWriteReport to emptySyncReport
            },
            userPreferences = prefs,
            settingsInitializer = { _, _, _ -> false }
        )
        val syncResult = SyncResult()
        adapter.onPerformSync(account, Bundle.EMPTY, authority, provider, syncResult)
        assertTrue(syncRan)
        // A best-effort settings failure is not an auth or network failure.
        assertEquals(0L, syncResult.stats.numAuthExceptions)
        assertEquals(0L, syncResult.stats.numIoExceptions)
        assertTrue(prefs.lastSyncSuccessAtMillis > 0L)
        assertNull(prefs.lastSyncErrorCode)
    }
}
