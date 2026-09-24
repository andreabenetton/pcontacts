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
        intervalHours: Long = 12,
        syncEnabled: Boolean = true,
        conflicts: Int = 0
    ) = syncHealth(running, failed, lastSync, outbox, intervalHours, now, syncEnabled, conflicts)

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

    @Test fun sync_switched_off_is_its_own_state_and_never_overdue() {
        assertEquals(SyncHealth.OFF, health(lastSync = LastSyncSummary(now - 100 * hour), syncEnabled = false))
        assertEquals(SyncHealth.RUNNING, health(running = true, lastSync = null, syncEnabled = false))
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

    @Test fun a_recent_run_that_did_not_converge_is_not_overdue() {
        val convergedLongAgo = LastSyncSummary(syncedAtMillis = now - 90 * hour, lastRunAtMillis = now - hour / 2)
        assertEquals(SyncHealth.UP_TO_DATE, health(lastSync = convergedLongAgo, intervalHours = 12))
        val ranLongAgo = LastSyncSummary(syncedAtMillis = now - 90 * hour, lastRunAtMillis = now - 30 * hour)
        assertEquals(SyncHealth.OVERDUE, health(lastSync = ranLongAgo, intervalHours = 12))
    }

    @Test fun an_unresolved_conflict_is_named_whatever_the_run_times() {
        assertEquals(SyncHealth.CONFLICT, health(conflicts = 1))
        val convergedLongAgo = LastSyncSummary(syncedAtMillis = now - 90 * hour, lastRunAtMillis = now - hour / 2)
        assertEquals(SyncHealth.CONFLICT, health(lastSync = convergedLongAgo, conflicts = 2))
        assertEquals(SyncHealth.CONFLICT, health(outbox = OutboxStats(pending = 1, quarantined = 0), conflicts = 1))
        assertEquals(SyncHealth.ATTENTION, health(outbox = OutboxStats(pending = 0, quarantined = 1), conflicts = 1))
    }

    @Test fun the_running_headline_names_each_stage_and_falls_back_when_it_is_unknown() {
        assertEquals(R.string.settings_sync_running, runningHeadlineRes(null))
        assertEquals(R.string.settings_sync_running, runningHeadlineRes(SyncProgress(done = 3, total = 7)))
        assertEquals(
            R.string.sync_stage_connecting,
            runningHeadlineRes(SyncProgress(done = 0, total = 0, stage = SyncStage.CONNECTING))
        )
        assertEquals(
            R.string.sync_stage_sending,
            runningHeadlineRes(SyncProgress(done = 1, total = 3, stage = SyncStage.SENDING))
        )
        assertEquals(
            R.string.sync_stage_checking,
            runningHeadlineRes(SyncProgress(done = 0, total = 0, stage = SyncStage.CHECKING))
        )
        assertEquals(
            R.string.sync_stage_downloading,
            runningHeadlineRes(SyncProgress(done = 3, total = 7, stage = SyncStage.DOWNLOADING))
        )
        assertEquals(
            R.string.sync_stage_saving,
            runningHeadlineRes(SyncProgress(done = 0, total = 0, stage = SyncStage.SAVING))
        )
    }

    @Test fun a_contact_removed_on_this_phone_asks_delete_on_proton_or_put_back() {
        val removed = ConflictInfo("c1", null, "removed on this phone", localRemoved = true)
        val edited = ConflictInfo("c2", null, "fullName")
        assertEquals(
            ConflictDialogTexts(
                R.string.conflict_dialog_local_removed,
                R.string.conflict_delete_on_proton,
                R.string.conflict_put_back
            ),
            conflictDialogTexts(removed)
        )
        assertEquals(R.string.conflict_use_local, conflictDialogTexts(edited).useLocal)
        assertEquals(R.string.conflict_detail_local_removed, conflictDetailRes(listOf(edited, removed)))
        assertEquals(R.string.conflict_detail, conflictDetailRes(listOf(edited)))
    }
}
