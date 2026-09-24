// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.sync.contacts

import android.accounts.Account
import android.content.ContentProviderClient
import android.content.Context
import android.provider.ContactsContract
import io.pcontacts.core.contactswriter.LinkedContactCandidates
import io.pcontacts.core.contactswriter.LinkedContactCreator
import io.pcontacts.core.contactswriter.LinkedContactName
import io.pcontacts.core.contactswriter.LinkedContactSummary
import io.pcontacts.core.contactswriter.LinkedContactsReader
import io.pcontacts.core.contactswriter.LinkedContactsScanner
import io.pcontacts.core.contactswriter.LinkedField
import io.pcontacts.core.contactswriter.LinkedFieldsWriter
import io.pcontacts.core.contactswriter.OrphanContactMover
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Entry points for the one-way linked-contact import (ADR-0023). Each
 * call acquires the ContactsProvider for its own duration; nothing is
 * cached between the preview and the import, so the cluster is always
 * re-read from what Android currently aggregates.
 */
object LinkedContactsBootstrap {

    /** Every aggregate with something to bring into Proton, for the import list. */
    suspend fun scanLinkedContacts(context: Context, account: Account): List<LinkedContactSummary> =
        withContactsProvider(context) { LinkedContactsScanner(it).scan(account) }

    /** Null when the aggregate [contactId] no longer exists. */
    suspend fun loadCandidates(context: Context, account: Account, contactId: Long): LinkedContactCandidates? =
        withContactsProvider(context) { LinkedContactsReader(it).read(account, contactId) }

    suspend fun importFields(context: Context, account: Account, rawContactId: Long, fields: List<LinkedField>) =
        withContactsProvider(context) { LinkedFieldsWriter(it).append(account, rawContactId, fields) }

    /** Creates the Proton copy of aggregate [contactId]; returns the new `RawContacts._ID`. */
    suspend fun createContact(
        context: Context,
        account: Account,
        contactId: Long,
        name: LinkedContactName?,
        fields: List<LinkedField>
    ): Long = withContactsProvider(context) { LinkedContactCreator(it).create(account, contactId, name, fields) }

    /**
     * Moves the orphan `PHONE` RawContact into [account] (ADR-0026); false when it is gone
     * or no longer in the orphan account. The next sync creates it on Proton.
     */
    suspend fun moveContact(context: Context, account: Account, rawContactId: Long): Boolean =
        withContactsProvider(context) { OrphanContactMover(it).move(account, rawContactId) }

    private suspend fun <T> withContactsProvider(context: Context, block: (ContentProviderClient) -> T): T =
        withContext(Dispatchers.IO) {
            val provider = context.contentResolver.acquireContentProviderClient(ContactsContract.AUTHORITY)
                ?: error("ContactsProvider unavailable")
            try {
                block(provider)
            } finally {
                provider.close()
            }
        }
}
