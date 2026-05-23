// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.contactswriter

import android.accounts.Account
import android.content.ContentProviderClient
import android.content.ContentProviderResult
import android.provider.ContactsContract
import android.provider.ContactsContract.RawContacts

/**
 * Applies a list of intents to the system ContactsContract provider.
 * Owns the only `provider.applyBatch` call in the codebase; per
 * ADR-0010 and the CLAUDE.md anti-patterns, no other module makes this
 * call directly.
 *
 * Each chunk is one binder transaction; a chunk that fails throws and
 * leaves earlier chunks committed. The caller decides whether to retry
 * the unwritten chunks or bail. The MVP SyncAdapter bails on first
 * failure and records the partial progress in SyncResult.
 */
class BatchApplier(private val provider: ContentProviderClient) {

    /**
     * Applies all intents; returns an op-count summary. Caller-side
     * counting is fine because the per-intent op count is fixed by
     * ContactsContractOps.
     */
    fun apply(account: Account, intents: List<RawContactOpIntent>): ApplyResult {
        if (intents.isEmpty()) return ApplyResult(insertedContacts = 0, updatedContacts = 0, deletedContacts = 0)

        val chunks = BatchPlanner.plan(account, intents)
        var totalResults = 0
        for (chunk in chunks) {
            val results: Array<ContentProviderResult> =
                provider.applyBatch(ContactsContract.AUTHORITY, ArrayList(chunk))
            totalResults += results.size
        }

        val inserted = intents.count { it is RawContactOpIntent.CreateContact }
        val updated = intents.count { it is RawContactOpIntent.UpdateContact }
        val deleted = intents.count { it is RawContactOpIntent.DeleteContact }
        return ApplyResult(inserted, updated, deleted, totalOpsApplied = totalResults)
    }

    /**
     * Deletes every RawContact this account owns. Used by the logout
     * flow — the user expects their Proton contacts to vanish from
     * the system Contacts app when they sign out.
     *
     * Returns the row count the provider reports as deleted; 0 when
     * the account was empty (or the provider returned null for any
     * reason). Caller-IS-SYNCADAPTER is set on the URI so the rows
     * don't leave tombstones (a tombstoned RawContact would resurrect
     * as a duplicate if the user signs back in with the same account).
     */
    fun deleteAllForAccount(account: Account): Int {
        val uri = SyncAdapterUri.decorate(RawContacts.CONTENT_URI, account.name, account.type)
        return provider.delete(
            uri,
            "${RawContacts.ACCOUNT_TYPE} = ? AND ${RawContacts.ACCOUNT_NAME} = ?",
            arrayOf(account.type, account.name)
        )
    }
}

data class ApplyResult(
    val insertedContacts: Int,
    val updatedContacts: Int,
    val deletedContacts: Int,
    val totalOpsApplied: Int = 0
)
