// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.contactswriter

import android.accounts.Account
import android.content.ContentProviderClient
import android.database.Cursor
import android.provider.ContactsContract.RawContacts

/**
 * Reads the RawContacts our account owns from ContactsProvider — the
 * authoritative source for which local rows exist. Used by the sync
 * engine before computing the diff — that's how Update intents learn
 * which RawContacts._ID to reuse (preserving aggregated state per
 * ADR-0010), and how the engine detects rows that vanished or were
 * duplicated behind its back.
 *
 * The cursor-parsing step is split out so it can be exercised with
 * `MatrixCursor` under pure-Robolectric tests, without spinning up a
 * fake ContactsProvider.
 */
class RawContactReader(private val provider: ContentProviderClient) {

    /**
     * Full per-SOURCE_ID row state, including tombstones (DELETED=1)
     * and duplicate rows sharing a SOURCE_ID.
     */
    fun readExistingState(account: Account): ExistingRawContacts {
        val cursor: Cursor? = provider.query(
            RawContacts.CONTENT_URI,
            arrayOf(RawContacts._ID, RawContacts.SOURCE_ID, RawContacts.DELETED),
            "${RawContacts.ACCOUNT_TYPE} = ? AND ${RawContacts.ACCOUNT_NAME} = ?",
            arrayOf(account.type, account.name),
            null
        )
        return cursor?.use(RawContactReader::parse) ?: ExistingRawContacts(emptyMap())
    }

    /** Flat SOURCE_ID → canonical RawContacts._ID view of [readExistingState]. */
    fun readExisting(account: Account): Map<String, Long> =
        readExistingState(account).canonicalIds()

    companion object {
        /** Pure parser — visible for tests so we don't need a live ContentProvider. */
        fun parse(cursor: Cursor): ExistingRawContacts {
            if (cursor.count == 0) return ExistingRawContacts(emptyMap())
            val idIdx = cursor.getColumnIndexOrThrow(RawContacts._ID)
            val sourceIdx = cursor.getColumnIndexOrThrow(RawContacts.SOURCE_ID)
            val deletedIdx = cursor.getColumnIndexOrThrow(RawContacts.DELETED)
            val out = HashMap<String, MutableList<ExistingRawContact>>(cursor.count)
            while (cursor.moveToNext()) {
                val sourceId = cursor.getString(sourceIdx) ?: continue   // skip rows without SOURCE_ID
                out.getOrPut(sourceId) { ArrayList(1) } += ExistingRawContact(
                    rawContactId = cursor.getLong(idIdx),
                    deleted = cursor.getInt(deletedIdx) == 1
                )
            }
            return ExistingRawContacts(out)
        }
    }
}
