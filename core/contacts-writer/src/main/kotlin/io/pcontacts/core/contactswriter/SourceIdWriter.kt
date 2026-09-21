// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.contactswriter

import android.accounts.Account
import android.content.ContentProviderClient
import android.content.ContentValues
import android.provider.ContactsContract.RawContacts

/**
 * Writes the Proton-assigned id onto a locally-created RawContact's
 * SOURCE_ID after the CREATE reaches the server (ADR-0010, ADR-0017).
 *
 * Without this, a contact created on the phone keeps SOURCE_ID = null,
 * so the next pull cannot match it to the server copy and inserts a
 * second RawContact — a stray, never-syncing orphan (Android aggregates
 * the two by name, so it is invisible but real). Writing SOURCE_ID back
 * lets the pull recognise the row as already synced.
 *
 * The URI is decorated with `caller_is_syncadapter=true` per ADR-0010.
 */
class SourceIdWriter(private val provider: ContentProviderClient) {

    fun writeSourceId(account: Account, rawContactId: Long, sourceId: String) {
        val uri = SyncAdapterUri.decorate(RawContacts.CONTENT_URI, account.name, account.type)
        val values = ContentValues(1).apply {
            put(RawContacts.SOURCE_ID, sourceId)
        }
        provider.update(
            uri,
            values,
            "${RawContacts._ID} = ?",
            arrayOf(rawContactId.toString())
        )
    }
}
