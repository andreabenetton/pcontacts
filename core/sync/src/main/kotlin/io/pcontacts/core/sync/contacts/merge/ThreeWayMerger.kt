// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.sync.contacts.merge

import io.pcontacts.core.protoncontacts.ContactPatch
import io.pcontacts.core.protoncontacts.DecryptedContact
import io.pcontacts.core.protoncontacts.DecryptedPhoto
import io.pcontacts.core.protoncontacts.PhotoHash

/**
 * Per-field three-way merge with user escalation on same-field
 * conflicts (ADR-0017 §3B + §3C).
 *
 * Given three snapshots:
 *   - `base`: the last-known server state at the time the local edit
 *     was made (reconstructed from the stored payload hash or
 *     re-fetched from the server).
 *   - `server`: the current server state (just fetched).
 *   - `local`: the current local state (from ContactsContract).
 *
 * Per-field resolution:
 *   - Server unchanged (field equals base) → local wins.
 *   - Local unchanged (field equals base) → server wins.
 *   - Both changed to the same value → no conflict; use that value.
 *   - Both changed to different values → conflict.
 *
 * Multi-value fields (emails, phones, addresses, imAccounts) use
 * set-based merge: disjoint additions auto-merge; disjoint removals
 * auto-merge; contradicting changes to the same entry conflict. An
 * address is identified by its full component tuple; a changed TYPE is
 * a modification, never a second address (ADR-0017 second amendment).
 *
 * The photo merges through digests: the server side changed when the
 * server photo's digest differs from the base's `serverPhotoHash`, the
 * local side when the local bytes' digest differs from the base's
 * `localPhotoHash` (the provider re-encodes photos, so the two digests
 * of one photo never agree with each other — only with themselves).
 */
object ThreeWayMerger {

    data class MergeInput(
        val base: DecryptedContact,
        val server: DecryptedContact,
        val local: DecryptedContact
    )

    sealed interface MergeResult {
        data class AutoMerged(val merged: DecryptedContact) : MergeResult
        data class Conflicted(
            val partial: DecryptedContact,
            val conflicts: List<FieldConflict>
        ) : MergeResult
    }

    fun merge(input: MergeInput): MergeResult {
        val conflicts = mutableListOf<FieldConflict>()

        val fullName = mergeScalar(
            "fullName", input.base.fullName, input.server.fullName, input.local.fullName, conflicts
        )

        val structuredName = mergeScalar(
            "structuredName", input.base.structuredName, input.server.structuredName,
            input.local.structuredName, conflicts
        )

        val emails = mergeSet(
            "emails", input.base.emails, input.server.emails, input.local.emails,
            { it.address }, conflicts
        )

        val phones = mergeSet(
            "phones", input.base.phones, input.server.phones, input.local.phones,
            { it.number }, conflicts
        )

        val addresses = mergeSet(
            "addresses",
            input.base.addresses,
            input.server.addresses,
            input.local.addresses,
            { ContactPatch.addressKey(it) },
            conflicts
        )

        val organization = mergeScalar(
            "organization", input.base.organization, input.server.organization,
            input.local.organization, conflicts
        )

        val notes = mergeScalar(
            "notes", input.base.notes, input.server.notes, input.local.notes, conflicts
        )

        val imAccounts = mergeSet(
            "imAccounts", input.base.imAccounts, input.server.imAccounts, input.local.imAccounts,
            { "${it.handle}|${it.protocol}" }, conflicts
        )

        val photo = mergePhoto(input, conflicts)

        val merged = input.local.copy(
            fullName = fullName,
            structuredName = structuredName,
            emails = emails,
            phones = phones,
            addresses = addresses,
            organization = organization,
            notes = notes ?: emptyList(),
            imAccounts = imAccounts,
            photo = photo,
            serverPhotoHash = photo?.let { PhotoHash.of(it.data) },
            verified = input.server.verified,
            cardCount = input.server.cardCount,
            unverifiedCardCount = input.server.unverifiedCardCount,
            cards = input.server.cards
        )

        return if (conflicts.isEmpty()) {
            MergeResult.AutoMerged(merged)
        } else {
            MergeResult.Conflicted(merged, conflicts)
        }
    }

    private fun mergePhoto(input: MergeInput, conflicts: MutableList<FieldConflict>): DecryptedPhoto? {
        val serverHash = input.server.serverPhotoHash ?: input.server.photo?.let { PhotoHash.of(it.data) }
        val localHash = input.local.photo?.let { PhotoHash.of(it.data) }
        val serverChanged = serverHash != input.base.serverPhotoHash
        val localChanged = localHash != input.base.localPhotoHash
        return when {
            !localChanged -> input.server.photo
            !serverChanged -> input.local.photo
            serverHash == localHash -> input.server.photo
            else -> {
                conflicts += FieldConflict("photo", serverHash, localHash)
                input.local.photo
            }
        }
    }

    private fun <T> mergeScalar(
        fieldName: String,
        base: T?,
        server: T?,
        local: T?,
        conflicts: MutableList<FieldConflict>
    ): T? {
        if (server == base) return local
        if (local == base) return server
        if (server == local) return server
        conflicts += FieldConflict(fieldName, server?.toString(), local?.toString())
        return local
    }

    private fun <T, K> mergeSet(
        fieldName: String,
        base: List<T>,
        server: List<T>,
        local: List<T>,
        keyFn: (T) -> K,
        conflicts: MutableList<FieldConflict>
    ): List<T> {
        val baseByKey = base.associateBy(keyFn)
        val serverByKey = server.associateBy(keyFn)
        val localByKey = local.associateBy(keyFn)

        val allKeys = (baseByKey.keys + serverByKey.keys + localByKey.keys)
        val result = mutableListOf<T>()
        val conflicted = false

        for (key in allKeys) {
            val b = baseByKey[key]
            val s = serverByKey[key]
            val l = localByKey[key]

            when {
                // Present in all three — check for modifications
                b != null && s != null && l != null -> {
                    if (s == b) result += l          // server unchanged → local wins
                    else if (l == b) result += s     // local unchanged → server wins
                    else if (s == l) result += s     // same change
                    else {
                        conflicts += FieldConflict(fieldName, s.toString(), l.toString())
                        result += l
                    }
                }
                // Added on server only
                b == null && s != null && l == null -> result += s
                // Added on local only
                b == null && s == null && l != null -> result += l
                // Added on both
                b == null && s != null && l != null -> {
                    if (s == l) result += s
                    else {
                        result += l
                        result += s
                    }
                }
                // Removed on server only
                b != null && s == null && l != null -> {
                    if (l == b) { /* server deleted, local unchanged → accept deletion */ }
                    else {
                        conflicts += FieldConflict(fieldName, null, l.toString())
                        result += l
                    }
                }
                // Removed on local only
                b != null && s != null && l == null -> {
                    if (s == b) { /* local deleted, server unchanged → accept deletion */ }
                    else {
                        conflicts += FieldConflict(fieldName, s.toString(), null)
                        result += s
                    }
                }
                // Removed on both
                b != null && s == null && l == null -> { /* both deleted */ }
                // Not in any — shouldn't happen given allKeys construction
                else -> {}
            }
        }

        return result
    }
}
