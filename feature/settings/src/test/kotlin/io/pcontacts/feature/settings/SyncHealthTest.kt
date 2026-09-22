// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncHealthTest {

    private val hour = 3_600_000L
    private val now = 100 * hour
    private val quiet = OutboxStats(pending = 0, quarantined = 0)

    private fun health(
        running: Boolean = false,
        failed: Boolean = false,
        lastSync: LastSyncSummary? = LastSyncSummary(syncedAtMillis = now - hour),
        outbox: OutboxStats = quiet,
        intervalHours: Long = 12
    ) = syncHealth(running, failed, lastSync, outbox, intervalHours, now)

    @Test fun up_to_date_only_when_nothing_is_running_failed_pending_or_stale() {
        assertEquals(SyncHealth.UP_TO_DATE, health())
        assertEquals(SyncHealth.RUNNING, health(running = true))
        assertEquals(SyncHealth.FAILED, health(failed = true))
        assertEquals(SyncHealth.FAILED, health(lastSync = LastSyncSummary(now - hour, failureMessage = "offline")))
        assertEquals(SyncHealth.ATTENTION, health(outbox = OutboxStats(pending = 0, quarantined = 1)))
        assertEquals(SyncHealth.ATTENTION, health(lastSync = LastSyncSummary(now - hour, failedContacts = 2)))
        assertEquals(SyncHealth.PENDING, health(outbox = OutboxStats(pending = 3, quarantined = 0)))
        assertEquals(SyncHealth.NEVER, health(lastSync = null))
        assertEquals(SyncHealth.NEVER, health(lastSync = LastSyncSummary(syncedAtMillis = null)))
    }

    @Test fun overdue_after_twice_the_interval() {
        assertEquals(SyncHealth.UP_TO_DATE, health(lastSync = LastSyncSummary(now - 23 * hour), intervalHours = 12))
        assertEquals(SyncHealth.OVERDUE, health(lastSync = LastSyncSummary(now - 25 * hour), intervalHours = 12))
        assertEquals(SyncHealth.OVERDUE, health(lastSync = LastSyncSummary(now - 3 * hour), intervalHours = 1))
    }

    @Test fun running_and_failed_outrank_everything_else() {
        val stale = LastSyncSummary(now - 90 * hour, failureMessage = "offline")
        val busy = OutboxStats(pending = 2, quarantined = 2)
        assertEquals(SyncHealth.RUNNING, health(running = true, failed = true, lastSync = stale, outbox = busy))
        assertEquals(SyncHealth.FAILED, health(failed = true, lastSync = stale, outbox = busy))
        assertEquals(SyncHealth.ATTENTION, health(lastSync = LastSyncSummary(now - 90 * hour), outbox = busy))
    }
}
