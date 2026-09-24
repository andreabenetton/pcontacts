// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.app.settings

import io.pcontacts.core.sync.contacts.SyncPhase
import io.pcontacts.feature.settings.SyncProgress
import io.pcontacts.feature.settings.SyncStage

/** The Settings card's progress, from the phase code and counts the sync engines persist. */
internal object SyncProgressMapper {

    /**
     * Null when no phase is recorded (no run, or one that has not reached its first phase).
     * A phase maps to the card stage of the same name; the test holds every phase to one.
     */
    fun progress(phaseCode: String?, done: Int, total: Int): SyncProgress? {
        val phase = SyncPhase.entries.find { it.code == phaseCode } ?: return null
        val stage = SyncStage.entries.find { it.name == phase.name } ?: return null
        return SyncProgress(done = done, total = total, stage = stage)
    }
}
