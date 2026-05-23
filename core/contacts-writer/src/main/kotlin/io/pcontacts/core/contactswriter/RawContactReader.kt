// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.contactswriter

import android.accounts.Account
import android.content.ContentProviderClient
import android.database.Cursor
import android.provider.ContactsContract.RawContacts

/**
 * Returns the SOURCE_ID → RawContacts._ID map for all RawContacts under
 * our account type. Used by the sync engine before computing the diff
 * — that's how Update intents learn which RawContacts._ID to reuse
 * (preserving aggregated state per ADR-0010).
 *
 * The cursor-parsing step is split out so it can be exercised with
 * `MatrixCursor` under pure-Robolectric tests, without spinning up a
 * fake ContactsProvider.
 */
class RawContactReader(private val provider: ContentProviderClient) {

    fun readExisting(account: Account): Map<String, Long> {
        val cursor: Cursor? = provider.query(
            RawContacts.CONTENT_URI,
            arrayOf(RawContacts._ID, RawContacts.SOURCE_ID),
            "${RawContacts.ACCOUNT_TYPE} = ? AND ${RawContacts.ACCOUNT_NAME} = ?",
            arrayOf(account.type, account.name),
            null
        )
        return cursor?.use(RawContactReader::parse) ?: emptyMap()
    }

    companion object {
        /** Pure parser — visible for tests so we don't need a live ContentProvider. */
        fun parse(cursor: Cursor): Map<String, Long> {
            if (cursor.count == 0) return emptyMap()
            val idIdx = cursor.getColumnIndexOrThrow(RawContacts._ID)
            val sourceIdx = cursor.getColumnIndexOrThrow(RawContacts.SOURCE_ID)
            val out = HashMap<String, Long>(cursor.count)
            while (cursor.moveToNext()) {
                val sourceId = cursor.getString(sourceIdx) ?: continue   // skip rows without SOURCE_ID
                val rawId = cursor.getLong(idIdx)
                out[sourceId] = rawId
            }
            return out
        }
    }
}
