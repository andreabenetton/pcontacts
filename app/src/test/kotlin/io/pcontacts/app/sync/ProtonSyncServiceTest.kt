// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.app.sync

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import io.pcontacts.app.account.ProtonAuthenticatorService
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ProtonSyncServiceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun exported(service: Class<*>): Boolean =
        context.packageManager.getServiceInfo(ComponentName(context, service), 0).exported

    @Test fun manifest_declares_the_sync_service_not_exported() {
        assertFalse(exported(ProtonSyncService::class.java))
    }

    @Test fun manifest_declares_the_authenticator_service_not_exported() {
        assertFalse(exported(ProtonAuthenticatorService::class.java))
    }

    @Test fun onBind_returns_the_binder_only_for_the_sync_adapter_action() {
        val service = Robolectric.buildService(ProtonSyncService::class.java).create().get()

        assertNotNull(service.onBind(Intent("android.content.SyncAdapter")))
        assertNull(service.onBind(Intent()))
        assertNull(service.onBind(Intent("android.accounts.AccountAuthenticator")))
    }
}
