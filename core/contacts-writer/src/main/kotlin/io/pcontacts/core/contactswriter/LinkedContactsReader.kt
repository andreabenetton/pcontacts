// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.contactswriter

import android.accounts.Account
import android.content.ContentProviderClient
import android.provider.ContactsContract.RawContacts

/**
 * Read-only view of one aggregate Contact for ADR-0023: finds the
 * Proton RawContact inside it and diffs every other account's
 * RawContact against it. The cluster is computed from
 * `RawContacts.CONTACT_ID` at call time and never persisted — Android
 * re-aggregates freely (ADR-0022).
 *
 * Nothing here writes. Sibling rows are parsed by [RawContactDataReader],
 * whose fixed set of standard mimetypes is the ADR-0023 allowlist:
 * app-specific action rows (WhatsApp / Telegram chips, our own
 * "Send via Proton Mail" row) fall through its `when` and are ignored.
 */
class LinkedContactsReader(private val provider: ContentProviderClient) {

    private val dataReader = RawContactDataReader(provider)

    /** Returns null when the aggregate holds no RawContact of [account]. */
    fun read(account: Account, contactId: Long): LinkedContactCandidates? {
        val members = queryMembers(contactId)
        val proton = members
            .filter { it.accountType == account.type && it.accountName == account.name }
            .maxByOrNull { it.hasSourceId }
            ?: return null
        val protonRow = dataReader.read(proton.rawContactId, sourceId = "")
        val siblings = members
            .filter { it.accountType != account.type }
            .mapNotNull { member ->
                dataReader.read(member.rawContactId, sourceId = "")?.let { member.accountType to it }
            }
        return LinkedContactCandidates(
            protonRawContactId = proton.rawContactId,
            candidates = LinkedContactDiff.candidates(protonRow, siblings)
        )
    }

    private data class Member(
        val rawContactId: Long,
        val accountType: String?,
        val accountName: String?,
        val hasSourceId: Boolean
    )

    private fun queryMembers(contactId: Long): List<Member> {
        val cursor = provider.query(
            RawContacts.CONTENT_URI,
            PROJECTION,
            "${RawContacts.CONTACT_ID} = ? AND ${RawContacts.DELETED} = 0",
            arrayOf(contactId.toString()),
            null
        ) ?: return emptyList()
        return cursor.use {
            val out = mutableListOf<Member>()
            while (it.moveToNext()) {
                out += Member(
                    rawContactId = it.getLong(0),
                    accountType = it.getString(1),
                    accountName = it.getString(2),
                    hasSourceId = !it.isNull(3)
                )
            }
            out
        }
    }

    private companion object {
        val PROJECTION = arrayOf(
            RawContacts._ID,
            RawContacts.ACCOUNT_TYPE,
            RawContacts.ACCOUNT_NAME,
            RawContacts.SOURCE_ID
        )
    }
}
