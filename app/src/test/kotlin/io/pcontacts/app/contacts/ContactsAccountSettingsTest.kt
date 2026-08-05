// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.app.contacts

import android.accounts.Account
import android.app.Application
import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import androidx.test.core.app.ApplicationProvider
import io.pcontacts.app.account.PROTON_ACCOUNT_TYPE
import io.pcontacts.core.logging.Logger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ContactsAccountSettingsTest {

    private val account = Account("test@proton.me", PROTON_ACCOUNT_TYPE)
    private lateinit var recording: RecordingProvider
    private lateinit var resolver: ContentResolver

    @Before fun setUp() {
        recording = Robolectric.buildContentProvider(RecordingProvider::class.java)
            .create("com.android.contacts")
            .get()
        resolver = ApplicationProvider.getApplicationContext<Application>().contentResolver
    }

    @Test
    fun insert_targets_settings_uri_with_syncadapter_decoration() {
        val ok = ContactsAccountSettings.ensureVisibleAndSyncable(resolver, account)
        assertTrue(ok)
        assertEquals(1, recording.inserts.size)
        val uri = recording.inserts.single().first
        assertEquals("com.android.contacts", uri.authority)
        assertEquals("settings", uri.path?.trimStart('/'))
        assertEquals("true", uri.getQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER))
        assertEquals(account.name, uri.getQueryParameter(ContactsContract.Settings.ACCOUNT_NAME))
        assertEquals(account.type, uri.getQueryParameter(ContactsContract.Settings.ACCOUNT_TYPE))
    }

    @Test
    fun insert_writes_account_key_should_sync_and_ungrouped_visible() {
        ContactsAccountSettings.ensureVisibleAndSyncable(resolver, account)
        val values = recording.inserts.single().second!!
        assertEquals(account.name, values.getAsString(ContactsContract.Settings.ACCOUNT_NAME))
        assertEquals(account.type, values.getAsString(ContactsContract.Settings.ACCOUNT_TYPE))
        assertEquals(1, values.getAsInteger(ContactsContract.Settings.SHOULD_SYNC))
        assertEquals(1, values.getAsInteger(ContactsContract.Settings.UNGROUPED_VISIBLE))
    }

    @Test
    fun repeated_calls_are_safe_and_touch_nothing_else() {
        assertTrue(ContactsAccountSettings.ensureVisibleAndSyncable(resolver, account))
        assertTrue(ContactsAccountSettings.ensureVisibleAndSyncable(resolver, account))
        assertEquals(2, recording.inserts.size)
        assertEquals(recording.inserts[0].second, recording.inserts[1].second)
        // The helper must never delete, update, or otherwise mutate contact rows.
        assertEquals(0, recording.deletes)
        assertEquals(0, recording.updates)
    }

    @Test
    fun provider_failure_returns_false_and_logs_instead_of_throwing() {
        val boom = IllegalStateException("broken OEM provider")
        recording.throwOnInsert = boom
        val logger = RecordingLogger()
        val ok = ContactsAccountSettings.ensureVisibleAndSyncable(resolver, account, logger)
        assertFalse(ok)
        assertEquals(1, logger.errors.size)
        assertSame(boom, logger.errors.single().first)
    }
}

class RecordingProvider : ContentProvider() {
    val inserts = mutableListOf<Pair<Uri, ContentValues?>>()
    var deletes = 0
    var updates = 0
    var throwOnInsert: RuntimeException? = null

    override fun onCreate() = true
    override fun query(u: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? = null
    override fun getType(u: Uri): String? = null

    override fun insert(u: Uri, v: ContentValues?): Uri? {
        throwOnInsert?.let { throw it }
        inserts += u to v
        return u
    }

    override fun delete(u: Uri, s: String?, a: Array<String>?): Int {
        deletes++
        return 0
    }

    override fun update(u: Uri, v: ContentValues?, s: String?, a: Array<String>?): Int {
        updates++
        return 0
    }
}

class RecordingLogger : Logger {
    val errors = mutableListOf<Pair<Throwable?, String>>()

    override fun debug(throwable: Throwable?, msg: () -> String) = Unit
    override fun info(throwable: Throwable?, msg: () -> String) = Unit
    override fun warn(throwable: Throwable?, msg: () -> String) = Unit

    override fun error(throwable: Throwable?, msg: () -> String) {
        errors += throwable to msg()
    }
}
