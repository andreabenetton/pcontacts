// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.contactswriter

import android.accounts.Account
import android.content.ContentProviderClient
import android.content.ContentValues
import android.provider.ContactsContract.RawContacts

/**
 * Undoes a local deletion the user cancelled before it reached Proton
 * (ADR-0022): the RawContact rows still carrying the tombstone
 * (`DELETED = 1`) for the source id are made live again through the
 * sync-adapter URI (ADR-0010), with `DIRTY` cleared so the restore
 * itself is not queued as a change. Their Data rows were never
 * touched by the deletion, so the contact reappears as it was.
 * Returns how many rows were restored; zero means the provider has
 * already purged the tombstone and the contact must be re-fetched.
 */
class TombstoneRestorer(private val provider: ContentProviderClient) {

    fun restore(account: Account, sourceId: String): Int {
        val uri = SyncAdapterUri.decorate(RawContacts.CONTENT_URI, account.name, account.type)
        val values = ContentValues(2).apply {
            put(RawContacts.DELETED, 0)
            put(RawContacts.DIRTY, 0)
        }
        return provider.update(
            uri,
            values,
            "${RawContacts.SOURCE_ID} = ? AND ${RawContacts.ACCOUNT_NAME} = ? AND " +
                "${RawContacts.ACCOUNT_TYPE} = ? AND ${RawContacts.DELETED} = 1",
            arrayOf(sourceId, account.name, account.type)
        )
    }
}
