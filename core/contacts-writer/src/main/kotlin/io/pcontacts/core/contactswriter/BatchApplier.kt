// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.contactswriter

import android.accounts.Account
import android.content.ContentProviderClient
import android.content.ContentProviderResult
import android.os.TransactionTooLargeException
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
 *
 * One contact too large to be written at all does not fail the rest: it
 * is left out and named in [ApplyResult.tooLargeSourceIds] (review
 * 2026-11-18). That is a contact with more operations than a batch may
 * hold, or one over the byte budget whose own batch Binder refuses.
 */
class BatchApplier(private val provider: ContentProviderClient) {

    /**
     * Applies all intents; returns an op-count summary. Caller-side
     * counting is fine because the per-intent op count is fixed by
     * ContactsContractOps.
     */
    fun apply(account: Account, intents: List<RawContactOpIntent>): ApplyResult {
        if (intents.isEmpty()) return ApplyResult(insertedContacts = 0, updatedContacts = 0, deletedContacts = 0)

        val plan = BatchPlanner.plan(account, intents)
        val tooLarge = plan.tooManyOps.toMutableList()
        var totalResults = 0
        for (batch in plan.batches) {
            try {
                @Suppress("DEPRECATION")
                val results: Array<ContentProviderResult> = provider.applyBatch(ArrayList(batch.ops))
                totalResults += results.size
            } catch (e: TransactionTooLargeException) {
                // A batch within the budget that Binder refuses is the shared buffer being full,
                // not this contact: that still fails the run and is tried again.
                if (!batch.overBudget) throw e
                tooLarge += batch.intents
            }
        }

        val written = intents.filter { it !in tooLarge }
        return ApplyResult(
            insertedContacts = written.count { it is RawContactOpIntent.CreateContact },
            updatedContacts = written.count { it is RawContactOpIntent.UpdateContact },
            deletedContacts = written.count { it is RawContactOpIntent.DeleteContact },
            totalOpsApplied = totalResults,
            tooLargeSourceIds = tooLarge.mapNotNull(::sourceIdOf)
        )
    }

    private fun sourceIdOf(intent: RawContactOpIntent): String? = when (intent) {
        is RawContactOpIntent.CreateContact -> intent.row.sourceId
        is RawContactOpIntent.UpdateContact -> intent.row.sourceId
        is RawContactOpIntent.DeleteContact, is RawContactOpIntent.DeleteRawContact -> null
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
    val totalOpsApplied: Int = 0,
    /** Contacts left out because one of them alone is more than the provider can be handed. */
    val tooLargeSourceIds: List<String> = emptyList()
)
