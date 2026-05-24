// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.sync.contacts

/**
 * Per-run summary the SyncAdapter surfaces in SyncResult counters and
 * any future UI ("last sync: 142 contacts, 3 new"). All counts are
 * post-apply.
 */
data class SyncReport(
    val totalServer: Int,
    val inserted: Int,
    val updated: Int,
    val deleted: Int,
    val unchanged: Int,
    val unverifiedCount: Int = 0
) {
    fun isNoOp(): Boolean = inserted == 0 && updated == 0 && deleted == 0
}
