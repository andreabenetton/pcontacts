// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.storage

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class SharedPreferencesUserPreferencesTest {

    private fun prefs() =
        SharedPreferencesUserPreferences(ApplicationProvider.getApplicationContext())

    @Test
    fun lastSyncSuccess_defaults_to_zero_and_round_trips() {
        assertEquals(0L, prefs().lastSyncSuccessAtMillis)
        prefs().lastSyncSuccessAtMillis = 1_700_000_000_000L
        assertEquals(1_700_000_000_000L, prefs().lastSyncSuccessAtMillis)
    }

    @Test
    fun lastSyncErrorCode_defaults_null_round_trips_and_clears() {
        assertNull(prefs().lastSyncErrorCode)
        prefs().lastSyncErrorCode = "app_version"
        assertEquals("app_version", prefs().lastSyncErrorCode)
        prefs().lastSyncErrorCode = null
        assertNull(prefs().lastSyncErrorCode)
    }
}
