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
    fun every_offered_sync_interval_round_trips() {
        for (hours in listOf(1L, 3L, 6L, 12L, 24L)) {
            prefs().syncIntervalHours = hours
            assertEquals(hours, prefs().syncIntervalHours)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun an_interval_that_is_not_offered_is_refused() {
        prefs().syncIntervalHours = 2L
    }

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

    @Test
    fun lastSyncFailedContacts_defaults_zero_and_round_trips() {
        assertEquals(0, prefs().lastSyncFailedContacts)
        prefs().lastSyncFailedContacts = 3
        assertEquals(3, prefs().lastSyncFailedContacts)
    }

    @Test
    fun syncProgress_defaults_idle_and_round_trips() {
        assertNull(prefs().syncProgressPhase)
        assertEquals(0, prefs().syncProgressDone)
        assertEquals(0, prefs().syncProgressTotal)
        prefs().syncProgressPhase = "downloading"
        prefs().syncProgressDone = 120
        prefs().syncProgressTotal = 898
        assertEquals(120, prefs().syncProgressDone)
        assertEquals(898, prefs().syncProgressTotal)
        assertEquals("downloading", prefs().syncProgressPhase)
        prefs().syncProgressPhase = null
        assertNull(prefs().syncProgressPhase)
    }

    @Test
    fun advisory_check_is_off_by_default_and_its_state_round_trips() {
        assertEquals(false, prefs().advisoryCheckEnabled)
        assertEquals(0L, prefs().lastAdvisoryCheckAtMillis)
        assertEquals(null, prefs().advisoryResultJson)
        assertEquals("", prefs().advisoryNotifiedIds)
        prefs().advisoryCheckEnabled = true
        prefs().lastAdvisoryCheckAtMillis = 5L
        prefs().advisoryResultJson = "{}"
        prefs().advisoryNotifiedIds = "GHSA-1,GHSA-2"
        assertEquals(true, prefs().advisoryCheckEnabled)
        assertEquals(5L, prefs().lastAdvisoryCheckAtMillis)
        assertEquals("{}", prefs().advisoryResultJson)
        assertEquals("GHSA-1,GHSA-2", prefs().advisoryNotifiedIds)
        prefs().advisoryResultJson = null
        assertEquals(null, prefs().advisoryResultJson)
        assertEquals("", prefs().advisoryMutedKeys)
        prefs().advisoryMutedKeys = "a:b:1|GHSA-1"
        assertEquals("a:b:1|GHSA-1", prefs().advisoryMutedKeys)
    }

    @Test
    fun vulnerabilityNoticeVersionCode_defaults_zero_and_round_trips() {
        assertEquals(0, prefs().vulnerabilityNoticeVersionCode)
        prefs().vulnerabilityNoticeVersionCode = 19
        assertEquals(19, prefs().vulnerabilityNoticeVersionCode)
    }

    @Test
    fun syncProblemsNotified_defaults_zero_and_round_trips() {
        assertEquals(0, prefs().syncProblemsNotified)
        prefs().syncProblemsNotified = 3
        assertEquals(3, prefs().syncProblemsNotified)
    }

    @Test
    fun systemContactsNoticeDismissed_defaults_false_and_round_trips() {
        assertEquals(false, prefs().systemContactsNoticeDismissed)
        prefs().systemContactsNoticeDismissed = true
        assertEquals(true, prefs().systemContactsNoticeDismissed)
    }

    @Test
    fun clearSyncState_resets_sync_fields_and_keeps_device_preferences() {
        prefs().lastSyncSuccessAtMillis = 1_700_000_000_000L
        prefs().lastSyncRunAtMillis = 1_700_000_000_500L
        prefs().lastSyncErrorCode = "reauth"
        prefs().lastSyncFailedContacts = 2
        prefs().syncProgressPhase = "sending"
        prefs().syncProgressDone = 5
        prefs().syncProgressTotal = 9
        prefs().secretsStorageUpgraded = true
        prefs().syncIntervalHours = 6L
        prefs().systemContactsNoticeDismissed = true

        prefs().clearSyncState()

        assertEquals(true, prefs().secretsStorageUpgraded)

        assertEquals(0L, prefs().lastSyncSuccessAtMillis)
        assertEquals(0L, prefs().lastSyncRunAtMillis)
        assertNull(prefs().lastSyncErrorCode)
        assertEquals(0, prefs().lastSyncFailedContacts)
        assertNull(prefs().syncProgressPhase)
        assertEquals(0, prefs().syncProgressDone)
        assertEquals(0, prefs().syncProgressTotal)
        assertEquals(6L, prefs().syncIntervalHours)
        assertEquals(true, prefs().systemContactsNoticeDismissed)
    }
}
