// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.app.intent

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.ContactsContract
import io.pcontacts.app.account.PROTON_ACCOUNT_TYPE
import io.pcontacts.core.contactswriter.PContactsMimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
class SendViaProtonMailActivityTest {

    private lateinit var provider: FakeContactsProvider
    private val ourRow: Uri = Uri.parse("content://${ContactsContract.AUTHORITY}/data/42")

    @Before fun setUp() {
        provider = Robolectric.buildContentProvider(FakeContactsProvider::class.java)
            .create(ContactsContract.AUTHORITY)
            .get()
    }

    private fun launch(uri: Uri): Intent? {
        val activity = Robolectric.buildActivity(
            SendViaProtonMailActivity::class.java,
            Intent(Intent.ACTION_VIEW, uri)
        ).setup().get()
        assertTrue(activity.isFinishing)
        return shadowOf(activity).nextStartedActivity
    }

    private fun seedOurRow(email: String, accountType: String = PROTON_ACCOUNT_TYPE) {
        provider.types[ourRow] = PContactsMimeTypes.SEND_VIA_PROTON_MAIL
        provider.rows[ourRow] = email to accountType
    }

    @Test fun wrong_authority_finishes_without_querying() {
        assertNull(launch(Uri.parse("content://evil.provider/data/42")))
        assertEquals(0, provider.queries)
    }

    @Test fun non_data_path_finishes_without_querying() {
        provider.types[Uri.parse("content://${ContactsContract.AUTHORITY}/raw_contacts/1")] =
            PContactsMimeTypes.SEND_VIA_PROTON_MAIL

        assertNull(launch(Uri.parse("content://${ContactsContract.AUTHORITY}/raw_contacts/1")))
        assertEquals(0, provider.queries)
    }

    @Test fun wrong_mimetype_finishes_without_querying() {
        provider.types[ourRow] = ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE
        provider.rows[ourRow] = "alice@proton.me" to PROTON_ACCOUNT_TYPE

        assertNull(launch(ourRow))
        assertEquals(0, provider.queries)
    }

    @Test fun foreign_account_row_finishes_without_starting_an_activity() {
        seedOurRow("alice@proton.me", accountType = "com.whatsapp")

        assertNull(launch(ourRow))
        assertEquals(1, provider.queries)
    }

    @Test fun malformed_address_finishes() {
        seedOurRow("not an address")

        assertNull(launch(ourRow))
    }

    @Test fun valid_row_starts_sendto_with_a_mailto_built_from_parts() {
        seedOurRow("user+tag@proton.me")

        val started = launch(ourRow)!!

        assertEquals(Intent.ACTION_SENDTO, started.action)
        assertEquals("ch.protonmail.android", started.`package`)
        assertEquals(Uri.fromParts("mailto", "user+tag@proton.me", null), started.data)
        assertEquals("user+tag@proton.me", started.data!!.schemeSpecificPart)
    }
}

/** A ContactsProvider that answers `getType` and a DATA1 query for seeded rows only. */
class FakeContactsProvider : ContentProvider() {
    val types = HashMap<Uri, String>()

    /** email to account type */
    val rows = HashMap<Uri, Pair<String, String>>()
    var queries = 0

    override fun onCreate() = true
    override fun getType(uri: Uri): String? = types[uri]

    override fun query(uri: Uri, p: Array<String>?, selection: String?, args: Array<String>?, o: String?): Cursor {
        queries++
        val cursor = MatrixCursor(arrayOf(ContactsContract.Data.DATA1))
        val row = rows[uri] ?: return cursor
        val mimeType = args?.getOrNull(0)
        val accountType = args?.getOrNull(1)
        if (mimeType == types[uri] && accountType == row.second) cursor.addRow(arrayOf(row.first))
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<String>?): Int = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<String>?): Int = 0
}
