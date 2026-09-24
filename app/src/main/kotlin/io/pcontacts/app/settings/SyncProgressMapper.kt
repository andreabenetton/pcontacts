// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.app.settings

import io.pcontacts.core.sync.contacts.SyncPhase
import io.pcontacts.feature.settings.SyncProgress
import io.pcontacts.feature.settings.SyncStage

/** The Settings card's progress, from the phase code and counts the sync engines persist. */
internal object SyncProgressMapper {

    /** Null when no phase is recorded (no run, or one that has not reached its first phase). */
    fun progress(phaseCode: String?, done: Int, total: Int): SyncProgress? {
        val stage = when (SyncPhase.entries.find { it.code == phaseCode }) {
            SyncPhase.SENDING -> SyncStage.SENDING
            SyncPhase.CHECKING -> SyncStage.CHECKING
            SyncPhase.DOWNLOADING -> SyncStage.DOWNLOADING
            SyncPhase.SAVING -> SyncStage.SAVING
            null -> return null
        }
        return SyncProgress(done = done, total = total, stage = stage)
    }
}
