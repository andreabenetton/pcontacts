// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.contactswriter

import android.accounts.Account
import android.content.ContentProviderClient
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.Im
import android.provider.ContactsContract.CommonDataKinds.Nickname
import android.provider.ContactsContract.CommonDataKinds.Note
import android.provider.ContactsContract.CommonDataKinds.Organization
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.CommonDataKinds.Website
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts

/**
 * One aggregate Contact with something to bring into Proton (ADR-0023
 * in-app discovery): either it has no Proton copy at all or its Proton
 * copy lacks [newFieldCount] fields that linked rows carry.
 * [sourceAccountTypes] names every linked provider in the aggregate,
 * including ones (Telegram) whose rows hold no importable field.
 */
data class LinkedContactSummary(
    val contactId: Long,
    val displayName: String?,
    val sourceAccountTypes: List<String?>,
    val hasProtonCopy: Boolean,
    val newFieldCount: Int
)

/**
 * Finds every aggregate worth showing in the import list with two
 * provider reads: all live RawContacts, then every allowlisted Data
 * row. The diff itself is [LinkedContactDiff], exactly as for a single
 * contact. Read-only; nothing is cached or persisted.
 */
class LinkedContactsScanner(private val provider: ContentProviderClient) {

    fun scan(account: Account): List<LinkedContactSummary> {
        val aggregates = queryMembers()
            .groupBy { it.contactId }
            .filterValues { members -> members.any { it.accountType != account.type } }
        if (aggregates.isEmpty()) return emptyList()
        val wanted = aggregates.values.flatten().mapTo(HashSet()) { it.rawContactId }
        val rows = queryRows().filterKeys { it in wanted }
        return summarize(account, aggregates, rows)
    }

    data class Member(
        val rawContactId: Long,
        val contactId: Long,
        val accountType: String?,
        val accountName: String?,
        val displayName: String?,
        val hasSourceId: Boolean
    )

    private fun queryMembers(): List<Member> {
        val cursor = provider.query(
            RawContacts.CONTENT_URI,
            MEMBER_PROJECTION,
            "${RawContacts.DELETED} = 0",
            null,
            null
        ) ?: return emptyList()
        return cursor.use {
            val out = mutableListOf<Member>()
            while (it.moveToNext()) {
                out += Member(
                    rawContactId = it.getLong(0),
                    contactId = it.getLong(1),
                    accountType = it.getString(2),
                    accountName = it.getString(3),
                    displayName = it.getString(4),
                    hasSourceId = !it.isNull(5)
                )
            }
            out
        }
    }

    private fun queryRows(): Map<Long, ContactRow> {
        val placeholders = ALLOWED_MIMETYPES.joinToString(",") { "?" }
        val cursor = provider.query(
            Data.CONTENT_URI,
            arrayOf(Data.RAW_CONTACT_ID) + RawContactDataReader.PROJECTION,
            "${Data.MIMETYPE} IN ($placeholders)",
            ALLOWED_MIMETYPES.toTypedArray(),
            null
        ) ?: return emptyMap()
        return cursor.use(RawContactDataReader::parseByRawContact)
    }

    companion object {
        private val MEMBER_PROJECTION = arrayOf(
            RawContacts._ID,
            RawContacts.CONTACT_ID,
            RawContacts.ACCOUNT_TYPE,
            RawContacts.ACCOUNT_NAME,
            RawContacts.DISPLAY_NAME_PRIMARY,
            RawContacts.SOURCE_ID
        )

        /** The ADR-0023 allowlist; photos are deliberately left out of the bulk read. */
        internal val ALLOWED_MIMETYPES = listOf(
            StructuredName.CONTENT_ITEM_TYPE,
            Email.CONTENT_ITEM_TYPE,
            Phone.CONTENT_ITEM_TYPE,
            StructuredPostal.CONTENT_ITEM_TYPE,
            Organization.CONTENT_ITEM_TYPE,
            Note.CONTENT_ITEM_TYPE,
            Im.CONTENT_ITEM_TYPE,
            Event.CONTENT_ITEM_TYPE,
            Nickname.CONTENT_ITEM_TYPE,
            Website.CONTENT_ITEM_TYPE
        )

        fun summarize(
            account: Account,
            aggregates: Map<Long, List<Member>>,
            rows: Map<Long, ContactRow>
        ): List<LinkedContactSummary> =
            aggregates
                .mapNotNull { (contactId, members) -> summarizeOne(account, contactId, members, rows) }
                .sortedWith(compareBy(nullsLast(String.CASE_INSENSITIVE_ORDER)) { it.displayName })

        private fun summarizeOne(
            account: Account,
            contactId: Long,
            members: List<Member>,
            rows: Map<Long, ContactRow>
        ): LinkedContactSummary? {
            val proton = members
                .filter { it.accountType == account.type && it.accountName == account.name }
                .maxByOrNull { it.hasSourceId }
            val siblings = members
                .filter { it.accountType != account.type }
                .mapNotNull { member -> rows[member.rawContactId]?.let { member.accountType to it } }
            if (siblings.isEmpty()) return null
            val candidates = LinkedContactDiff.candidates(proton?.let { rows[it.rawContactId] }, siblings)
            // A contact that is not in Proton needs at least one reachable field to be created.
            val usable = proton != null || candidates.any { it.field.reachesContact }
            if (candidates.isEmpty() || !usable) return null
            // Every linked provider, not only those that carried a field: a Telegram row has
            // no importable data of its own, but "also on Telegram" is worth showing.
            val providers = members.filter { it.accountType != account.type }.map { it.accountType }.distinct()
            return LinkedContactSummary(
                contactId = contactId,
                displayName = members.firstNotNullOfOrNull { it.displayName },
                sourceAccountTypes = providers,
                hasProtonCopy = proton != null,
                newFieldCount = candidates.size
            )
        }
    }
}
