// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.contactswriter

import android.accounts.Account
import android.content.ContentProviderOperation

/**
 * Packs intent-derived ops into batches that respect both
 *
 *   - the binder transaction limit (ADR-0010 caps at 450 ops/batch), and
 *   - the back-reference contract: `withValueBackReference` indices are
 *     absolute to the assembled batch, so a Create intent's three ops
 *     MUST land in the same chunk, and each chunk MUST re-anchor the
 *     RawContacts insert at the chunk's own starting offset.
 *
 * Implementation: we materialise each intent's ops with the current
 * batch-relative `baseIdx`. If adding them would overflow the chunk
 * AND the intent is a Create (back-ref sensitive), we close the current
 * chunk and re-build the intent with `baseIdx = 0` for the new chunk.
 */
object BatchPlanner {

    const val MAX_OPS_PER_BATCH = 450

    fun plan(
        account: Account,
        intents: List<RawContactOpIntent>,
        maxOpsPerBatch: Int = MAX_OPS_PER_BATCH
    ): List<List<ContentProviderOperation>> {
        require(maxOpsPerBatch > 0) { "maxOpsPerBatch must be positive" }

        val chunks = ArrayList<List<ContentProviderOperation>>()
        var current = ArrayList<ContentProviderOperation>()

        for (intent in intents) {
            var built = ContactsContractOps.build(account, intent, baseIdx = current.size)
            require(built.size <= maxOpsPerBatch) {
                "Single intent produced ${built.size} ops; exceeds maxOpsPerBatch=$maxOpsPerBatch"
            }

            if (current.size + built.size > maxOpsPerBatch && current.isNotEmpty()) {
                chunks += current
                current = ArrayList(maxOpsPerBatch)
                // Re-anchor back-refs for the new chunk — only matters when the
                // intent uses them, but cheap to redo unconditionally.
                built = ContactsContractOps.build(account, intent, baseIdx = 0)
            }
            current.addAll(built)
        }

        if (current.isNotEmpty()) chunks += current
        return chunks
    }
}
