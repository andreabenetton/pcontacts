// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.contactswriter

import android.accounts.Account
import android.content.ContentProviderClient
import android.content.ContentValues
import android.database.Cursor
import android.provider.ContactsContract.Groups

/**
 * Owns the lifecycle of `ContactsContract.Groups` rows under our
 * account. Plan §6 + §8 — Proton's `LabelID`s map to local Group
 * rows whose `_ID` the sync engine then writes into
 * `GroupMembership` Data rows on each contact.
 *
 * Single-direction reconciler:
 *   - For every Proton label not yet present locally, INSERT a
 *     Groups row (SOURCE_ID = Proton label id, TITLE = Proton
 *     label name).
 *   - For every existing Groups row whose SOURCE_ID isn't in the
 *     server's set, DELETE it (Proton-deleted labels disappear
 *     locally too).
 *   - Returns a fresh Map<protonLabelId, localGroupRowId> the
 *     engine uses to translate per-contact `ContactMetadataDto.labelIds`
 *     to `ContactRow.groupRowIds`.
 *
 * Every URI passes through SyncAdapterUri.decorate so deletes
 * don't leave tombstones (same rule as the RawContacts side per
 * ADR-0010).
 */
class LocalGroupsWriter(private val provider: ContentProviderClient) {

    /**
     * Reconciles the local Groups rows for `account` against the
     * server's `labels`. Returns the post-reconcile map of
     * `proton label id → local Groups._ID` for the engine to look up.
     */
    fun reconcile(account: Account, labels: List<ProtonLabel>): Map<String, Long> {
        val existing = readExistingMap(account)

        // INSERT: labels the server has that we don't.
        for (label in labels) {
            if (existing.containsKey(label.id)) continue
            val values = ContentValues().apply {
                put(Groups.ACCOUNT_NAME, account.name)
                put(Groups.ACCOUNT_TYPE, account.type)
                put(Groups.SOURCE_ID, label.id)
                put(Groups.TITLE, label.name)
                put(Groups.GROUP_VISIBLE, 1)
                put(Groups.SHOULD_SYNC, 1)
            }
            val uri = SyncAdapterUri.decorate(Groups.CONTENT_URI, account.name, account.type)
            provider.insert(uri, values)
        }

        // DELETE: rows whose SOURCE_ID is no longer in the server's set.
        val serverIds = labels.mapTo(HashSet(labels.size)) { it.id }
        for ((sourceId, _) in existing) {
            if (sourceId !in serverIds) {
                val uri = SyncAdapterUri.decorate(Groups.CONTENT_URI, account.name, account.type)
                provider.delete(
                    uri,
                    "${Groups.SOURCE_ID} = ? AND ${Groups.ACCOUNT_NAME} = ? AND ${Groups.ACCOUNT_TYPE} = ?",
                    arrayOf(sourceId, account.name, account.type)
                )
            }
        }

        // Re-read for the post-reconcile snapshot.
        return readExistingMap(account)
    }

    private fun readExistingMap(account: Account): Map<String, Long> {
        val cursor: Cursor? = provider.query(
            Groups.CONTENT_URI,
            arrayOf(Groups._ID, Groups.SOURCE_ID),
            "${Groups.ACCOUNT_TYPE} = ? AND ${Groups.ACCOUNT_NAME} = ?",
            arrayOf(account.type, account.name),
            null
        )
        return cursor?.use(::parseExisting) ?: emptyMap()
    }

    companion object {
        /** Pure parser — exposed for tests that synthesize a MatrixCursor. */
        fun parseExisting(cursor: Cursor): Map<String, Long> {
            if (cursor.count == 0) return emptyMap()
            val idIdx = cursor.getColumnIndexOrThrow(Groups._ID)
            val sourceIdx = cursor.getColumnIndexOrThrow(Groups.SOURCE_ID)
            val out = HashMap<String, Long>(cursor.count)
            while (cursor.moveToNext()) {
                val sourceId = cursor.getString(sourceIdx) ?: continue
                out[sourceId] = cursor.getLong(idIdx)
            }
            return out
        }
    }
}

/**
 * Caller-supplied per-label record. Keeps :core:contacts-writer
 * independent of :core:proton-api by not depending on
 * `ProtonLabelsApi.LabelDto`; the engine adapts.
 */
data class ProtonLabel(val id: String, val name: String)
