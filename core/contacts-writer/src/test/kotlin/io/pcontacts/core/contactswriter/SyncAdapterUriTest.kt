// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.contactswriter

import android.provider.ContactsContract
import android.provider.ContactsContract.RawContacts
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class SyncAdapterUriTest {

    @Test fun decorate_appends_all_three_required_sync_params() {
        val out = SyncAdapterUri.decorate(
            RawContacts.CONTENT_URI,
            accountName = "alice@proton.me",
            accountType = "io.pcontacts.account"
        )

        // Mandatory per ADR-0010 — without it, deletes resurrect as duplicates.
        assertEquals("true", out.getQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER))
        assertEquals("alice@proton.me", out.getQueryParameter(RawContacts.ACCOUNT_NAME))
        assertEquals("io.pcontacts.account", out.getQueryParameter(RawContacts.ACCOUNT_TYPE))
    }

    @Test fun decorate_preserves_underlying_authority_and_path() {
        val out = SyncAdapterUri.decorate(
            RawContacts.CONTENT_URI,
            accountName = "n",
            accountType = "t"
        )
        assertEquals(RawContacts.CONTENT_URI.authority, out.authority)
        assertEquals(RawContacts.CONTENT_URI.path, out.path)
    }
}
