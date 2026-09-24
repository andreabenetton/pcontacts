// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.contactswriter

import android.accounts.Account
import android.content.ContentProviderClient
import android.content.ContentUris
import android.provider.ContactsContract.RawContacts

/**
 * Creates the Proton copy of a contact that so far exists only in other
 * accounts (ADR-0023 amendment). The new RawContact carries the chosen
 * fields, no SOURCE_ID and DIRTY=1, so the next `detectChanges()` pass
 * queues it as a CREATE; it is pinned into the aggregate [contactId] so
 * it does not split from the rows it was copied from. Those rows are
 * never touched.
 */
class LinkedContactCreator(private val provider: ContentProviderClient) {

    /**
     * Returns the new `RawContacts._ID`. [fields] must include at least
     * one phone, email, address or IM account ([ContactRow]'s guard).
     */
    fun create(account: Account, contactId: Long, name: LinkedContactName?, fields: List<LinkedField>): Long {
        // sourceId is unused: buildCreateLocal deliberately writes no SOURCE_ID.
        val row = ContactRow(
            sourceId = "",
            displayName = name?.displayName,
            structuredName = name?.structuredName,
            emails = fields.filterIsInstance<LinkedField.EmailAddress>().map { it.address },
            phones = fields.filterIsInstance<LinkedField.PhoneNumber>().map { it.phone },
            addresses = fields.filterIsInstance<LinkedField.Address>().map { it.address },
            organization = fields.filterIsInstance<LinkedField.Org>().firstOrNull()?.organization,
            notes = fields.filterIsInstance<LinkedField.NoteText>().map { it.note },
            imAccounts = fields.filterIsInstance<LinkedField.Im>().map { it.account },
            birthday = fields.filterIsInstance<LinkedField.Birthday>().firstOrNull()?.date,
            anniversary = fields.filterIsInstance<LinkedField.Anniversary>().firstOrNull()?.date,
            nicknames = fields.filterIsInstance<LinkedField.NicknameText>().map { it.name },
            websites = fields.filterIsInstance<LinkedField.WebsiteUrl>().map { it.url }
        )
        val ops = ContactsContractOps.buildCreateLocal(account, row, keepWithRawContactId = anyRawContactOf(contactId))
        val results = provider.applyBatch(ArrayList(ops))
        return ContentUris.parseId(checkNotNull(results[0].uri) { "RawContacts insert returned no uri" })
    }

    private fun anyRawContactOf(contactId: Long): Long? =
        provider.query(
            RawContacts.CONTENT_URI,
            arrayOf(RawContacts._ID),
            "${RawContacts.CONTACT_ID} = ? AND ${RawContacts.DELETED} = 0",
            arrayOf(contactId.toString()),
            null
        )?.use { if (it.moveToFirst()) it.getLong(0) else null }
}
