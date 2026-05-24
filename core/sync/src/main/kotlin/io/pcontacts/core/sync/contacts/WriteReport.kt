// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.sync.contacts

/**
 * Per-push summary the SyncAdapter surfaces alongside [SyncReport].
 * All counts are post-attempt; [failed] entries remain in the outbox
 * for retry, [quarantined] entries are permanently side-lined.
 */
data class WriteReport(
    val pushed: Int = 0,
    val created: Int = 0,
    val updated: Int = 0,
    val deleted: Int = 0,
    val failed: Int = 0,
    val quarantined: Int = 0,
    val skippedGrace: Int = 0,
    val conflicted: Int = 0
) {
    fun isNoOp(): Boolean = pushed == 0 && failed == 0 && conflicted == 0

    operator fun plus(other: WriteReport) = WriteReport(
        pushed = pushed + other.pushed,
        created = created + other.created,
        updated = updated + other.updated,
        deleted = deleted + other.deleted,
        failed = failed + other.failed,
        quarantined = quarantined + other.quarantined,
        skippedGrace = skippedGrace + other.skippedGrace,
        conflicted = conflicted + other.conflicted
    )

    companion object {
        val EMPTY = WriteReport()
    }
}
