// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.contactswriter

/**
 * Pure-data description of what the diff layer wants the writer to do.
 * Separates the "decide what changed" logic (RawContactDiffer — pure
 * JVM, exhaustively unit-tested) from the "build the right
 * ContentProviderOperation list" mapping (ContactsContractOps —
 * Android-tied).
 */
sealed interface RawContactOpIntent {

    /** Server has a contact we've never written. Insert a fresh RawContact + Data rows. */
    data class CreateContact(val row: ContactRow) : RawContactOpIntent

    /**
     * Server's row matches an existing RawContact (by SOURCE_ID).
     * Per ADR-0010: delete child Data rows + reinsert, keep the
     * RawContacts._ID stable (preserves user-owned aggregate state).
     */
    data class UpdateContact(val rawContactId: Long, val row: ContactRow) : RawContactOpIntent

    /** Server no longer has a contact we previously wrote. Delete the whole RawContact. */
    data class DeleteContact(val sourceId: String) : RawContactOpIntent

    /**
     * Internal maintenance: remove one specific RawContact row from the
     * invalid duplicate state (several rows under our account sharing a
     * SOURCE_ID). Scoped to a RawContacts._ID so the canonical survivor
     * is untouched; the sync-adapter URI purges the row without leaving
     * DIRTY/DELETED state, so the cleanup is never mistaken for a user
     * deletion to propagate to Proton.
     */
    data class DeleteRawContact(val rawContactId: Long) : RawContactOpIntent
}
