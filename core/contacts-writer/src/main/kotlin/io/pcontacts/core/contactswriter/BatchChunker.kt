// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.contactswriter

import android.accounts.Account
import android.content.ContentProviderOperation
import android.os.Parcel

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
 *
 * A chunk is bounded in bytes too: the op count says nothing about the
 * photos and notes the ops carry, and one `applyBatch` travels in a single
 * Binder transaction, whose buffer is 1 MiB shared by the process. Each
 * intent is measured as it is parcelled; an intent never splits, and one
 * over the budget on its own travels alone (review 2026-11-17, F6).
 */
object BatchPlanner {

    const val MAX_OPS_PER_BATCH = 450

    /** `[A]` Well under Binder's 1 MiB transaction buffer, which the whole process shares. */
    const val MAX_BYTES_PER_BATCH = 384 * 1024

    fun plan(
        account: Account,
        intents: List<RawContactOpIntent>,
        maxOpsPerBatch: Int = MAX_OPS_PER_BATCH,
        maxBytesPerBatch: Int = MAX_BYTES_PER_BATCH
    ): List<List<ContentProviderOperation>> {
        require(maxOpsPerBatch > 0) { "maxOpsPerBatch must be positive" }

        val chunks = ArrayList<List<ContentProviderOperation>>()
        var current = ArrayList<ContentProviderOperation>()
        var currentBytes = 0

        for (intent in intents) {
            var built = ContactsContractOps.build(account, intent, baseIdx = current.size)
            require(built.size <= maxOpsPerBatch) {
                "Single intent produced ${built.size} ops; exceeds maxOpsPerBatch=$maxOpsPerBatch"
            }
            val builtBytes = parcelledSize(built)

            val overflows = current.size + built.size > maxOpsPerBatch || currentBytes + builtBytes > maxBytesPerBatch
            if (overflows && current.isNotEmpty()) {
                chunks += current
                current = ArrayList(maxOpsPerBatch)
                currentBytes = 0
                // Re-anchor back-refs for the new chunk — only matters when the
                // intent uses them, but cheap to redo unconditionally.
                built = ContactsContractOps.build(account, intent, baseIdx = 0)
            }
            current.addAll(built)
            currentBytes += builtBytes
        }

        if (current.isNotEmpty()) chunks += current
        return chunks
    }

    /** The bytes [ops] take in the Binder parcel `applyBatch` sends. */
    private fun parcelledSize(ops: List<ContentProviderOperation>): Int {
        val parcel = Parcel.obtain()
        return try {
            ops.forEach { it.writeToParcel(parcel, 0) }
            parcel.dataSize()
        } finally {
            parcel.recycle()
        }
    }
}
