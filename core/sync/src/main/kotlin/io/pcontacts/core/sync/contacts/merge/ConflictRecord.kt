// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.sync.contacts.merge

/**
 * Describes one or more field-level conflicts detected during a
 * three-way merge (ADR-0017 §3B). Stored as a JSON blob in
 * `ContactMapEntity.lastError` when `syncStatus == CONFLICT` —
 * avoids an additional Room migration.
 */
data class ConflictRecord(
    val protonContactId: String,
    val displayName: String?,
    val conflicts: List<FieldConflict>,
    val detectedAt: Long
)

data class FieldConflict(
    val fieldName: String,
    val serverValue: String?,
    val localValue: String?
)
