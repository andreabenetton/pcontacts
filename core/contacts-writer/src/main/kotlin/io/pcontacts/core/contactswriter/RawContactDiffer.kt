// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.contactswriter

/**
 * Pure-JVM decision logic: given the rows the caller wants applied,
 * the SOURCE_ID → RawContacts._ID map of what we already wrote, and
 * the set of sourceIds the server currently reports, produce the
 * minimal intent list to converge them.
 *
 * The caller pre-filters `target` to only include rows that need a
 * Create or Update (e.g. by comparing each row's content hash against
 * the Room mapping's stored hash). Anything the server still reports
 * but the caller omitted from `target` is treated as "unchanged" — no
 * intent emitted, no Data rows rewritten.
 *
 * Idempotency contract: with all hashes already matching, the sync
 * engine passes an empty `target` and the full server id set; this
 * function returns an empty list. The :app SyncAdapter's
 * round-trip test exercises this end-to-end on a real provider.
 */
object RawContactDiffer {

    fun diff(
        target: List<ContactRow>,
        existing: Map<String, Long>,
        serverSourceIds: Set<String>
    ): List<RawContactOpIntent> {
        val intents = ArrayList<RawContactOpIntent>(target.size + existing.size)

        for (row in target) {
            val existingRawId = existing[row.sourceId]
            if (existingRawId == null) {
                intents += RawContactOpIntent.CreateContact(row)
            } else {
                intents += RawContactOpIntent.UpdateContact(rawContactId = existingRawId, row = row)
            }
        }

        // Deletes — anything we previously wrote that the server no longer mentions.
        // Note: rows the server *did* mention but the caller skipped (unchanged)
        // are filtered out by `in serverSourceIds`.
        for ((sourceId, _) in existing) {
            if (sourceId !in serverSourceIds) {
                intents += RawContactOpIntent.DeleteContact(sourceId)
            }
        }

        return intents
    }
}
