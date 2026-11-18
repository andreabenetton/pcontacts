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
 *
 * One contact can be too much by itself: more operations than a batch may
 * hold, or more bytes than Binder carries. It is set apart, never mixed
 * into a batch with others, so that it alone fails and the contacts
 * around it are written (review 2026-11-18).
 */
object BatchPlanner {

    /** One `applyBatch`: its operations, the intents they came from, and whether it is over the byte budget. */
    class Batch(
        val ops: List<ContentProviderOperation>,
        val intents: List<RawContactOpIntent>,
        val overBudget: Boolean
    )

    /** [tooManyOps]: intents that no batch can hold; they are in none of [batches]. */
    class Plan(val batches: List<Batch>, val tooManyOps: List<RawContactOpIntent>)

    const val MAX_OPS_PER_BATCH = 450

    /**
     * `[A]` Well under Binder's 1 MiB transaction buffer, which the whole process shares. The CI
     * emulators on Android 8 and 13 refused a 347 KB batch that a Samsung on Android 11 took
     * (2026-11-17), so the budget leaves a wide margin: one contact with a photo near the 96 KiB
     * inline limit travels alone.
     */
    const val MAX_BYTES_PER_BATCH = 128 * 1024

    fun plan(
        account: Account,
        intents: List<RawContactOpIntent>,
        maxOpsPerBatch: Int = MAX_OPS_PER_BATCH,
        maxBytesPerBatch: Int = MAX_BYTES_PER_BATCH
    ): Plan {
        require(maxOpsPerBatch > 0) { "maxOpsPerBatch must be positive" }

        val batches = ArrayList<Batch>()
        val tooManyOps = ArrayList<RawContactOpIntent>()
        var ops = ArrayList<ContentProviderOperation>()
        var held = ArrayList<RawContactOpIntent>()
        var bytes = 0
        fun close() {
            if (ops.isNotEmpty()) batches += Batch(ops, held, overBudget = bytes > maxBytesPerBatch)
            ops = ArrayList()
            held = ArrayList()
            bytes = 0
        }

        for (intent in intents) {
            var built = ContactsContractOps.build(account, intent, baseIdx = ops.size)
            if (built.size > maxOpsPerBatch) {
                tooManyOps += intent
                continue
            }
            val builtBytes = parcelledSize(built)

            val overflows = ops.size + built.size > maxOpsPerBatch || bytes + builtBytes > maxBytesPerBatch
            if (overflows && ops.isNotEmpty()) {
                close()
                // Re-anchor back-refs for the new chunk — only matters when the
                // intent uses them, but cheap to redo unconditionally.
                built = ContactsContractOps.build(account, intent, baseIdx = 0)
            }
            ops.addAll(built)
            held += intent
            bytes += builtBytes
            // Over the budget by itself: nothing else joins it.
            if (bytes > maxBytesPerBatch) close()
        }

        close()
        return Plan(batches, tooManyOps)
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
