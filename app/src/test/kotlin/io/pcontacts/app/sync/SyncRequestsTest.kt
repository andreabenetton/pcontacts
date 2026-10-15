// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.app.sync

import android.accounts.Account
import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.provider.ContactsContract
import androidx.test.core.app.ApplicationProvider
import io.pcontacts.app.account.PROTON_ACCOUNT_TYPE
import io.pcontacts.core.storage.SharedPreferencesUserPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class SyncRequestsTest {

    private val account = Account("test@proton.me", PROTON_ACCOUNT_TYPE)
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before fun setUp() {
        ContentResolver.setMasterSyncAutomatically(true)
        ContentResolver.setSyncAutomatically(account, ContactsContract.AUTHORITY, true)
    }

    private fun requests(): Int = ShadowContentResolver.getStatus(account, ContactsContract.AUTHORITY)?.syncRequests ?: 0

    @Test fun requests_expedited_never_manual_when_sync_is_on() {
        assertTrue(SyncRequests.requestIfEnabled(context, account))

        val status = ShadowContentResolver.getStatus(account, ContactsContract.AUTHORITY)
        assertEquals(1, status.syncRequests)
        assertTrue(status.syncExtras.getBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED))
        assertFalse(status.syncExtras.getBoolean(ContentResolver.SYNC_EXTRAS_MANUAL))
    }

    @Test fun every_request_is_time_stamped_for_the_running_card() {
        val before = System.currentTimeMillis()

        SyncRequests.requestNow(context, account)

        val status = ShadowContentResolver.getStatus(account, ContactsContract.AUTHORITY)
        assertTrue(status.syncExtras.getBoolean(ContentResolver.SYNC_EXTRAS_MANUAL))
        assertTrue(SharedPreferencesUserPreferences(context).syncRequestedAtMillis >= before)
    }

    @Test fun makes_no_request_when_the_account_switch_is_off() {
        ContentResolver.setSyncAutomatically(account, ContactsContract.AUTHORITY, false)

        assertFalse(SyncRequests.requestIfEnabled(context, account))
        assertEquals(0, requests())
    }

    @Test fun makes_no_request_when_the_master_switch_is_off() {
        ContentResolver.setMasterSyncAutomatically(false)

        assertFalse(SyncRequests.requestIfEnabled(context, account))
        assertEquals(0, requests())
    }
}
