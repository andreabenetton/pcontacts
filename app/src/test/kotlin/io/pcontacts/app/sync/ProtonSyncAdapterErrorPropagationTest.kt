// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.app.sync

import android.accounts.Account
import android.app.Application
import android.content.ContentProvider
import android.content.ContentProviderClient
import android.content.ContentValues
import android.content.SyncResult
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import io.pcontacts.core.proton.api.http.HumanVerificationRequiredException
import io.pcontacts.core.sync.contacts.SyncReport
import io.pcontacts.core.sync.contacts.WriteReport
import io.pcontacts.core.sync.contacts.decrypt.DecryptUnavailableException
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ProtonSyncAdapterErrorPropagationTest {

    private val account = Account("test@proton.me", "io.pcontacts")
    private val extras = Bundle.EMPTY
    private val authority = "com.android.contacts"
    private lateinit var provider: ContentProviderClient

    @Before fun setUp() {
        Robolectric.buildContentProvider(StubProvider::class.java)
            .create("com.android.contacts")
        provider = ApplicationProvider.getApplicationContext<Application>()
            .contentResolver.acquireContentProviderClient("com.android.contacts")!!
    }

    private fun adapter(
        runner: suspend (android.content.Context, ContentProviderClient, Account) -> Pair<WriteReport, SyncReport>
    ): ProtonSyncAdapter =
        ProtonSyncAdapter(ApplicationProvider.getApplicationContext(), syncRunner = runner)

    @Test
    fun outOfMemoryError_propagates_out_of_onPerformSync() {
        val adapter = adapter { _, _, _ -> throw OutOfMemoryError("test OOM") }
        val syncResult = SyncResult()
        try {
            adapter.onPerformSync(account, extras, authority, provider, syncResult)
            fail("OutOfMemoryError must propagate, not be swallowed")
        } catch (_: OutOfMemoryError) {
            // expected
        }
        assertEquals("OOM must not be recorded as IO failure", 0L, syncResult.stats.numIoExceptions)
    }

    @Test
    fun stackOverflowError_propagates_out_of_onPerformSync() {
        val adapter = adapter { _, _, _ -> throw StackOverflowError("test SOE") }
        val syncResult = SyncResult()
        try {
            adapter.onPerformSync(account, extras, authority, provider, syncResult)
            fail("StackOverflowError must propagate, not be swallowed")
        } catch (_: StackOverflowError) {
            // expected
        }
        assertEquals("SOE must not be recorded as IO failure", 0L, syncResult.stats.numIoExceptions)
    }

    @Test
    fun runtimeException_is_caught_and_recorded_as_io_failure() {
        val adapter = adapter { _, _, _ -> throw IllegalStateException("boom") }
        val syncResult = SyncResult()
        adapter.onPerformSync(account, extras, authority, provider, syncResult)
        assertEquals(1L, syncResult.stats.numIoExceptions)
    }

    @Test
    fun decryptUnavailable_is_caught_as_auth_exception() {
        val adapter = adapter { _, _, _ -> throw DecryptUnavailableException("no key") }
        val syncResult = SyncResult()
        adapter.onPerformSync(account, extras, authority, provider, syncResult)
        assertEquals(1L, syncResult.stats.numAuthExceptions)
        assertEquals(0L, syncResult.stats.numIoExceptions)
    }

    @Test
    fun humanVerificationRequired_is_caught_as_auth_exception() {
        val adapter = adapter { _, _, _ -> throw HumanVerificationRequiredException() }
        val syncResult = SyncResult()
        adapter.onPerformSync(account, extras, authority, provider, syncResult)
        assertEquals(1L, syncResult.stats.numAuthExceptions)
        assertEquals(0L, syncResult.stats.numIoExceptions)
    }

    @Test
    fun successful_sync_records_stats() {
        val wr = WriteReport(
            pushed = 1,
            created = 0,
            updated = 1,
            deleted = 0,
            failed = 0,
            quarantined = 0,
            skippedGrace = 0,
            conflicted = 0
        )
        val rr = SyncReport(
            totalServer = 5,
            inserted = 2,
            updated = 1,
            deleted = 1,
            unchanged = 1
        )
        val adapter = adapter { _, _, _ -> wr to rr }
        val syncResult = SyncResult()
        adapter.onPerformSync(account, extras, authority, provider, syncResult)
        assertEquals(2L, syncResult.stats.numInserts)
        assertEquals(2L, syncResult.stats.numUpdates)
        assertEquals(1L, syncResult.stats.numDeletes)
        assertEquals(0L, syncResult.stats.numIoExceptions)
        assertEquals(0L, syncResult.stats.numAuthExceptions)
    }
}

class StubProvider : ContentProvider() {
    override fun onCreate() = true
    override fun query(u: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? = null
    override fun getType(u: Uri): String? = null
    override fun insert(u: Uri, v: ContentValues?): Uri? = null
    override fun delete(u: Uri, s: String?, a: Array<String>?) = 0
    override fun update(u: Uri, v: ContentValues?, s: String?, a: Array<String>?) = 0
}
