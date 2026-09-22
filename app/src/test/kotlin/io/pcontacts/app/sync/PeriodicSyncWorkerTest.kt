// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.app.sync

import android.accounts.Account
import android.accounts.AccountManager
import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.provider.ContactsContract
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import io.pcontacts.app.account.PROTON_ACCOUNT_TYPE
import kotlinx.coroutines.runBlocking
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
class PeriodicSyncWorkerTest {

    private lateinit var context: Context
    private val account = Account("test@proton.me", PROTON_ACCOUNT_TYPE)

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val config = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.DEBUG)
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        // The shadow defaults both switches to off; a signed-in device has them on.
        ContentResolver.setMasterSyncAutomatically(true)
    }

    private fun addAccount() {
        AccountManager.get(context).addAccountExplicitly(account, null, null)
        ContentResolver.setSyncAutomatically(account, ContactsContract.AUTHORITY, true)
    }

    private fun runWorker(): ListenableWorker.Result = runBlocking {
        TestListenableWorkerBuilder<PeriodicSyncWorker>(context).build().doWork()
    }

    private fun requests(): Int = ShadowContentResolver.getStatus(account, ContactsContract.AUTHORITY)?.syncRequests ?: 0

    @Test fun doWork_returns_success_when_no_accounts_exist() {
        assertEquals(ListenableWorker.Result.success(), runWorker())
    }

    @Test fun doWork_requests_an_expedited_sync_without_the_manual_extra() {
        addAccount()

        assertEquals(ListenableWorker.Result.success(), runWorker())

        val status = ShadowContentResolver.getStatus(account, ContactsContract.AUTHORITY)
        assertEquals(1, status.syncRequests)
        assertTrue(status.syncExtras.getBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED))
        assertFalse(
            "a timer is not the user: never bypass the sync settings",
            status.syncExtras.getBoolean(ContentResolver.SYNC_EXTRAS_MANUAL)
        )
    }

    @Test fun doWork_makes_no_request_when_the_accounts_sync_switch_is_off() {
        addAccount()
        ContentResolver.setSyncAutomatically(account, ContactsContract.AUTHORITY, false)

        assertEquals(ListenableWorker.Result.success(), runWorker())

        assertEquals(0, requests())
    }

    @Test fun doWork_makes_no_request_when_the_master_sync_switch_is_off() {
        addAccount()
        ContentResolver.setMasterSyncAutomatically(false)

        assertEquals(ListenableWorker.Result.success(), runWorker())

        assertEquals(0, requests())
    }
}
