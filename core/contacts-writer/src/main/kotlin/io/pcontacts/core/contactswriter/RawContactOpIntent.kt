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
}
