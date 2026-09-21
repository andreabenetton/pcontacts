// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.feature.settings

import java.util.concurrent.TimeUnit

/** What the status card's headline says about the sync, most urgent first. */
enum class SyncHealth { RUNNING, FAILED, ATTENTION, PENDING, NEVER, OVERDUE, UP_TO_DATE }

/**
 * Decides the headline from observable facts, so "Up to date" is a
 * claim with conditions: nothing running or failed, no change failed
 * permanently, nothing waiting in the outbox, and the last successful
 * run not older than [OVERDUE_FACTOR] times the chosen interval.
 */
fun syncHealth(
    running: Boolean,
    failed: Boolean,
    lastSync: LastSyncSummary?,
    outbox: OutboxStats,
    intervalHours: Long,
    nowMillis: Long
): SyncHealth {
    val syncedAt = lastSync?.syncedAtMillis
    return when {
        running -> SyncHealth.RUNNING
        failed || lastSync?.failureMessage != null -> SyncHealth.FAILED
        outbox.quarantined > 0 -> SyncHealth.ATTENTION
        outbox.pending > 0 -> SyncHealth.PENDING
        syncedAt == null -> SyncHealth.NEVER
        nowMillis - syncedAt > OVERDUE_FACTOR * TimeUnit.HOURS.toMillis(intervalHours) -> SyncHealth.OVERDUE
        else -> SyncHealth.UP_TO_DATE
    }
}

/** A periodic run may legitimately slip by one interval (Doze, no network); two is overdue. */
private const val OVERDUE_FACTOR = 2
