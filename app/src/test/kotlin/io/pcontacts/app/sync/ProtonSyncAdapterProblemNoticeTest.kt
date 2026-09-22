// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.app.sync

import android.Manifest
import android.accounts.Account
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.ContentProviderClient
import android.content.ContentResolver
import android.content.SyncResult
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import io.pcontacts.app.account.PROTON_ACCOUNT_TYPE
import io.pcontacts.app.notifications.NotificationChannels
import io.pcontacts.core.storage.InMemoryUserPreferences
import io.pcontacts.core.sync.contacts.SyncReport
import io.pcontacts.core.sync.contacts.WriteReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ProtonSyncAdapterProblemNoticeTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val prefs = InMemoryUserPreferences()
    private val account = Account("user@proton.me", PROTON_ACCOUNT_TYPE)

    private lateinit var provider: ContentProviderClient

    @Before fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        NotificationChannels.createAll(app)
        Robolectric.buildContentProvider(StubProvider::class.java).create("com.android.contacts")
        provider = app.contentResolver.acquireContentProviderClient("com.android.contacts")!!
    }

    private fun adapter(readFailed: Int, writeFailed: Int = 0, quarantined: Int = 0) = ProtonSyncAdapter(
        app,
        syncRunner = { _, _, _ ->
            WriteReport(failed = writeFailed, quarantined = quarantined) to
                SyncReport(totalServer = 4, inserted = 0, updated = 0, deleted = 0, unchanged = 4, failed = readFailed)
        },
        userPreferences = prefs,
        settingsInitializer = { _, _, _ -> true }
    )

    private fun run(adapter: ProtonSyncAdapter, manual: Boolean = false) {
        val extras = Bundle().apply { if (manual) putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true) }
        adapter.onPerformSync(account, extras, "com.android.contacts", provider, SyncResult())
    }

    private fun notifications() = shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications

    @Test fun a_background_run_with_problems_notifies_once_per_count_and_a_clean_run_clears_it() {
        run(adapter(readFailed = 2, quarantined = 1))
        val posted = notifications().single()
        assertEquals(NotificationChannels.ACTION_REQUIRED, posted.channelId)
        assertTrue(posted.extras.getCharSequence(Notification.EXTRA_TEXT).toString().startsWith("3 "))
        assertEquals(3, prefs.syncProblemsNotified)

        run(adapter(readFailed = 2, quarantined = 1))
        assertEquals(1, notifications().size)

        run(adapter(readFailed = 0))
        assertTrue(notifications().isEmpty())
        assertEquals(0, prefs.syncProblemsNotified)
    }

    @Test fun a_manual_run_stays_quiet_because_the_card_is_on_screen() {
        run(adapter(readFailed = 2), manual = true)
        assertTrue(notifications().isEmpty())
        assertEquals(0, prefs.syncProblemsNotified)
    }
}
