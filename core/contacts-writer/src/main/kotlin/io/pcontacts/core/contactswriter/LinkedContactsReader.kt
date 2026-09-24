// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.contactswriter

import android.accounts.Account
import android.content.ContentProviderClient
import android.provider.ContactsContract.RawContacts

/**
 * Read-only view of one aggregate Contact for ADR-0023: finds the
 * Proton RawContact inside it (if any) and diffs every other account's
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

    /** Returns null when the aggregate [contactId] no longer exists. */
    fun read(account: Account, contactId: Long): LinkedContactCandidates? {
        val members = queryMembers(contactId)
        if (members.isEmpty()) return null
        val proton = members
            .filter { it.accountType == account.type && it.accountName == account.name }
            .maxByOrNull { it.hasSourceId }
        val protonRow = proton?.let { dataReader.read(it.rawContactId, sourceId = "") }
        val siblings = members
            .filter { it.accountType != account.type }
            .mapNotNull { member ->
                dataReader.read(member.rawContactId, sourceId = "")?.let { member.accountType to it }
            }
        val name = if (proton == null) siblings.firstNotNullOfOrNull { (_, row) -> row.linkedName() } else null
        // ADR-0026: only without a Proton copy, only the exact orphan account.
        val movable = if (proton != null) {
            null
        } else {
            members
                .filter { OrphanPhoneAccount.matches(it.accountType, it.accountName) }
                .minOfOrNull { it.rawContactId }
        }
        return LinkedContactCandidates(
            protonRawContactId = proton?.rawContactId,
            name = name,
            candidates = LinkedContactDiff.candidates(protonRow, siblings),
            movableRawContactId = movable,
            uncarried = movable?.let { OrphanContactMover(provider).uncarried(it) }.orEmpty()
        )
    }

    private fun ContactRow.linkedName(): LinkedContactName? =
        if (displayName.isNullOrBlank() && structuredName == null) {
            null
        } else {
            LinkedContactName(displayName, structuredName)
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
