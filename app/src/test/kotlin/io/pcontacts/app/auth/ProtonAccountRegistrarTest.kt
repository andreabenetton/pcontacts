// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.app.auth

import android.accounts.AccountManager
import android.app.Application
import android.content.ContentResolver
import android.provider.ContactsContract
import androidx.test.core.app.ApplicationProvider
import io.pcontacts.app.account.PROTON_ACCOUNT_TYPE
import io.pcontacts.app.contacts.RecordingProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ProtonAccountRegistrarTest {

    private lateinit var context: Application
    private lateinit var recording: RecordingProvider

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        recording = Robolectric.buildContentProvider(RecordingProvider::class.java)
            .create("com.android.contacts")
            .get()
    }

    @Test
    fun register_adds_account_with_proton_uid_userdata() {
        val account = ProtonAccountRegistrar.register(context, uid = "uid-123", username = "user@proton.me")
        val manager = AccountManager.get(context)
        assertEquals(listOf(account), manager.getAccountsByType(PROTON_ACCOUNT_TYPE).toList())
        assertEquals("uid-123", manager.getUserData(account, "proton_uid"))
    }

    @Test
    fun register_marks_contacts_authority_syncable_and_automatic() {
        val account = ProtonAccountRegistrar.register(context, uid = "uid-123", username = "user@proton.me")
        assertEquals(1, ContentResolver.getIsSyncable(account, ContactsContract.AUTHORITY))
        assertTrue(ContentResolver.getSyncAutomatically(account, ContactsContract.AUTHORITY))
    }

    @Test
    fun register_writes_contacts_settings_row() {
        ProtonAccountRegistrar.register(context, uid = "uid-123", username = "user@proton.me")
        val (uri, values) = recording.inserts.single()
        assertEquals("settings", uri.path?.trimStart('/'))
        assertEquals("true", uri.getQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER))
        assertEquals(1, values!!.getAsInteger(ContactsContract.Settings.UNGROUPED_VISIBLE))
        assertEquals(1, values.getAsInteger(ContactsContract.Settings.SHOULD_SYNC))
    }

    @Test
    fun register_requests_manual_expedited_first_sync() {
        val account = ProtonAccountRegistrar.register(context, uid = "uid-123", username = "user@proton.me")
        val status = ShadowContentResolver.getStatus(account, ContactsContract.AUTHORITY)
        assertEquals(1, status.syncRequests)
        assertTrue(status.syncExtras.getBoolean(ContentResolver.SYNC_EXTRAS_MANUAL))
        assertTrue(status.syncExtras.getBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED))
    }

    @Test
    fun register_twice_keeps_existing_account_and_still_requests_sync() {
        val first = ProtonAccountRegistrar.register(context, uid = "uid-123", username = "user@proton.me")
        val second = ProtonAccountRegistrar.register(context, uid = "uid-456", username = "user@proton.me")
        assertEquals(first, second)
        val manager = AccountManager.get(context)
        assertEquals(1, manager.getAccountsByType(PROTON_ACCOUNT_TYPE).size)
        // Re-login refreshes the stored uid rather than replacing the account.
        assertEquals("uid-456", manager.getUserData(second, "proton_uid"))
        assertEquals(2, ShadowContentResolver.getStatus(second, ContactsContract.AUTHORITY).syncRequests)
    }
}
