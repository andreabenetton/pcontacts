// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.contactswriter

import android.accounts.Account
import android.content.ContentProviderClient
import android.content.ContentValues
import android.provider.ContactsContract.RawContacts

/**
 * Clears the DIRTY flag on a RawContact after the outbox has
 * captured the pending change (ADR-0017 §1C). The URI is
 * decorated with `caller_is_syncadapter=true` per ADR-0010.
 */
class DirtyFlagClearer(private val provider: ContentProviderClient) {

    fun clearDirty(account: Account, rawContactId: Long) {
        val uri = SyncAdapterUri.decorate(
            RawContacts.CONTENT_URI, account.name, account.type
        )
        val values = ContentValues(1).apply {
            put(RawContacts.DIRTY, 0)
        }
        provider.update(
            uri,
            values,
            "${RawContacts._ID} = ?",
            arrayOf(rawContactId.toString())
        )
    }
}
