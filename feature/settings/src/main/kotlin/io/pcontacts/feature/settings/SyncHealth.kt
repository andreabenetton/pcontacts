// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.feature.settings

import java.util.concurrent.TimeUnit

/** What the status card's headline says about the sync, most urgent first. */
enum class SyncHealth { RUNNING, OFF, FAILED, ATTENTION, CONFLICT, PENDING, NEVER, OVERDUE, UP_TO_DATE }

/**
 * Decides the headline from observable facts, so "Up to date" is a
 * claim with conditions: nothing running or failed, no change failed
 * permanently, no conflict awaiting the user's choice, no contact left
 * behind by the last run, nothing waiting
 * in the outbox, and the last completed run — the one "Last sync"
 * shows, converged or not — not older than [OVERDUE_FACTOR] times the
 * chosen interval. Overdue means runs stopped happening; a run that left
 * something to settle is reported by the states above it.
 */
// One parameter per fact the headline weighs; every call site names them.
@Suppress("LongParameterList")
fun syncHealth(
    running: Boolean,
    failed: Boolean,
    lastSync: LastSyncSummary?,
    outbox: OutboxStats,
    intervalHours: Long,
    nowMillis: Long,
    /** Both of Android's sync switches on; off means no automatic run is expected, so nothing is overdue. */
    syncEnabled: Boolean = true,
    /** Contacts waiting for the user's choice (edited on both sides, deleted on one); no run settles them. */
    conflicts: Int = 0
): SyncHealth {
    val syncedAt = lastSync?.lastRunAtMillis ?: lastSync?.syncedAtMillis
    return when {
        running -> SyncHealth.RUNNING
        !syncEnabled -> SyncHealth.OFF
        failed || lastSync?.failureMessage != null -> SyncHealth.FAILED
        outbox.quarantined > 0 || (lastSync?.failedContacts ?: 0) > 0 -> SyncHealth.ATTENTION
        conflicts > 0 -> SyncHealth.CONFLICT
        outbox.pending > 0 -> SyncHealth.PENDING
        syncedAt == null -> SyncHealth.NEVER
        nowMillis - syncedAt > OVERDUE_FACTOR * TimeUnit.HOURS.toMillis(intervalHours) -> SyncHealth.OVERDUE
        else -> SyncHealth.UP_TO_DATE
    }
}

/** A periodic run may legitimately slip by one interval (Doze, no network); two is overdue. */
private const val OVERDUE_FACTOR = 2
