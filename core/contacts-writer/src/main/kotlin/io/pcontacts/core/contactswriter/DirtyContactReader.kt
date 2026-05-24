// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.contactswriter

import android.accounts.Account
import android.content.ContentProviderClient
import android.database.Cursor
import android.provider.ContactsContract.RawContacts

/**
 * Reads RawContacts that Android has flagged as locally modified
 * (DIRTY=1) or locally deleted (DELETED=1) under our account.
 * The sync engine uses these flags to populate the outbox before
 * a push (ADR-0017 §1C).
 *
 * The cursor-parsing step is split out for MatrixCursor-based
 * testing, mirroring [RawContactReader].
 */
class DirtyContactReader(private val provider: ContentProviderClient) {

    fun readDirty(account: Account): List<DirtyContact> {
        val cursor: Cursor? = provider.query(
            RawContacts.CONTENT_URI,
            PROJECTION,
            "${RawContacts.ACCOUNT_TYPE} = ? AND ${RawContacts.ACCOUNT_NAME} = ? " +
                "AND (${RawContacts.DIRTY} = 1 OR ${RawContacts.DELETED} = 1)",
            arrayOf(account.type, account.name),
            null
        )
        return cursor?.use(DirtyContactReader::parse) ?: emptyList()
    }

    companion object {
        private val PROJECTION = arrayOf(
            RawContacts._ID,
            RawContacts.SOURCE_ID,
            RawContacts.DIRTY,
            RawContacts.DELETED
        )

        fun parse(cursor: Cursor): List<DirtyContact> {
            if (cursor.count == 0) return emptyList()
            val idIdx = cursor.getColumnIndexOrThrow(RawContacts._ID)
            val sourceIdx = cursor.getColumnIndexOrThrow(RawContacts.SOURCE_ID)
            val dirtyIdx = cursor.getColumnIndexOrThrow(RawContacts.DIRTY)
            val deletedIdx = cursor.getColumnIndexOrThrow(RawContacts.DELETED)
            val out = ArrayList<DirtyContact>(cursor.count)
            while (cursor.moveToNext()) {
                out += DirtyContact(
                    rawContactId = cursor.getLong(idIdx),
                    sourceId = cursor.getString(sourceIdx),
                    isDirty = cursor.getInt(dirtyIdx) == 1,
                    isDeleted = cursor.getInt(deletedIdx) == 1
                )
            }
            return out
        }
    }
}

data class DirtyContact(
    val rawContactId: Long,
    val sourceId: String?,
    val isDirty: Boolean,
    val isDeleted: Boolean
)
