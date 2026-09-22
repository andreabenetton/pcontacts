// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.app.account

import android.Manifest
import android.accounts.Account
import android.app.Application
import android.provider.ContactsContract
import androidx.test.core.app.ApplicationProvider
import io.pcontacts.app.contacts.RecordingProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class LogoutHelperTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    /**
     * On a device the acquire throws a SecurityException when Contacts access is missing; the
     * 1.x→2.0 upgrade sign-out and the Settings sign-out handle only the typed exception.
     */
    @Test fun sign_out_without_contacts_access_reports_the_missing_permission() {
        // A provider is reachable, so only the permission check can stop the sign-out.
        Robolectric.buildContentProvider(RecordingProvider::class.java).create(ContactsContract.AUTHORITY)
        shadowOf(app).denyPermissions(Manifest.permission.WRITE_CONTACTS)
        val helper = LogoutHelper(app, ioDispatcher = Dispatchers.Unconfined)
        val account = Account("user@proton.me", PROTON_ACCOUNT_TYPE)

        assertThrows(MissingContactsPermissionException::class.java) {
            runBlocking { helper.signOut(account) }
        }
    }
}
