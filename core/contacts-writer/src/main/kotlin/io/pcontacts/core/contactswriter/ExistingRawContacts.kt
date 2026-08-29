// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.contactswriter

/**
 * One RawContact row under the pcontacts account. `deleted` mirrors
 * RawContacts.DELETED — a tombstone awaiting deletion propagation. A
 * tombstone still counts as "present": the pull engine must not
 * recreate a contact the user just deleted locally.
 */
data class ExistingRawContact(val rawContactId: Long, val deleted: Boolean)

/**
 * Everything the pcontacts account currently owns in ContactsProvider,
 * grouped by SOURCE_ID. Unlike a flat `Map<String, Long>`, this shape
 * can represent the invalid duplicate state (several RawContacts
 * sharing one SOURCE_ID) so the sync engine can detect and repair it
 * instead of silently collapsing it.
 */
data class ExistingRawContacts(val rowsBySourceId: Map<String, List<ExistingRawContact>>) {

    fun contains(sourceId: String): Boolean = rowsBySourceId.containsKey(sourceId)

    /**
     * Deterministic canonical row for [sourceId]: [preferred] when it is
     * one of the live candidates, else the lowest live _ID, else the
     * lowest _ID overall (every row tombstoned). Null when the provider
     * has no row for the sourceId.
     */
    fun canonicalId(sourceId: String, preferred: Long? = null): Long? {
        val rows = rowsBySourceId[sourceId] ?: return null
        val live = rows.filter { !it.deleted }
        if (live.any { it.rawContactId == preferred }) return preferred
        val pool = live.ifEmpty { rows }
        return pool.minOf { it.rawContactId }
    }

    /** Extra row ids beyond [canonicalId] — non-empty only in the duplicate state. */
    fun duplicateIds(sourceId: String, canonicalId: Long): List<Long> =
        rowsBySourceId[sourceId].orEmpty()
            .map { it.rawContactId }
            .filter { it != canonicalId }

    /** Flat SOURCE_ID → canonical _ID view for consumers that assume uniqueness. */
    fun canonicalIds(): Map<String, Long> =
        rowsBySourceId.keys.associateWith { canonicalId(it)!! }
}
