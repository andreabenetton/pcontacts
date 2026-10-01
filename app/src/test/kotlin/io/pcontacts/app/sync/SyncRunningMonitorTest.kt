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

    @Test fun a_started_or_online_sync_is_not_waiting() {
        assertFalse(waitingForNetwork(active = true, pending = true, online = false))
        assertFalse(waitingForNetwork(active = false, pending = true, online = true))
        assertFalse(waitingForNetwork(active = false, pending = false, online = false))
    }
}
