// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.app.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncRunningMonitorTest {

    @Test fun a_pending_sync_without_a_network_is_waiting() {
        // Seen live 2026-10-02: offline, "Sync now" showed "Sync in progress" for good.
        assertTrue(waitingForNetwork(active = false, pending = true, online = false))
    }

    @Test fun android_pending_flag_counts_only_shortly_after_the_app_asked() {
        // Issue #70: the flag stayed set with no sync to run; the card said "Sync in progress" until a reboot.
        assertTrue(trustedPending(pending = true, online = true, sinceRequestMs = 10_000L))
        assertFalse(trustedPending(pending = true, online = true, sinceRequestMs = 5 * 60_000L))
        assertTrue(trustedPending(pending = true, online = false, sinceRequestMs = 30 * 60_000L))
        assertFalse(trustedPending(pending = true, online = false, sinceRequestMs = 2 * 60 * 60_000L))
        assertFalse(trustedPending(pending = false, online = true, sinceRequestMs = 0L))
    }

    @Test fun a_flag_with_no_request_on_record_is_stale() {
        assertFalse(trustedPending(pending = true, online = true, sinceRequestMs = System.currentTimeMillis()))
    }

    @Test fun a_started_or_online_sync_is_not_waiting() {
        assertFalse(waitingForNetwork(active = true, pending = true, online = false))
        assertFalse(waitingForNetwork(active = false, pending = true, online = true))
        assertFalse(waitingForNetwork(active = false, pending = false, online = false))
    }
}
