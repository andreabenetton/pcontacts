// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.sync.contacts

import android.accounts.Account
import android.content.ContentProviderClient
import android.content.Context
import android.provider.ContactsContract
import io.pcontacts.core.contactswriter.LinkedContactCandidates
import io.pcontacts.core.contactswriter.LinkedContactsReader
import io.pcontacts.core.contactswriter.LinkedField
import io.pcontacts.core.contactswriter.LinkedFieldsWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Entry points for the one-way linked-contact import (ADR-0023). Each
 * call acquires the ContactsProvider for its own duration; nothing is
 * cached between the preview and the import, so the cluster is always
 * re-read from what Android currently aggregates.
 */
object LinkedContactsBootstrap {

    /** Null when the aggregate [contactId] holds no RawContact of [account]. */
    suspend fun loadCandidates(context: Context, account: Account, contactId: Long): LinkedContactCandidates? =
        withContactsProvider(context) { LinkedContactsReader(it).read(account, contactId) }

    suspend fun importFields(context: Context, account: Account, rawContactId: Long, fields: List<LinkedField>) =
        withContactsProvider(context) { LinkedFieldsWriter(it).append(account, rawContactId, fields) }

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
