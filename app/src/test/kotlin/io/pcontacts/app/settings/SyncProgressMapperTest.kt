// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.app.settings

import io.pcontacts.core.sync.contacts.SyncPhase
import io.pcontacts.feature.settings.SyncProgress
import io.pcontacts.feature.settings.SyncStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncProgressMapperTest {

    @Test fun every_engine_phase_maps_to_its_stage_with_the_counts() {
        assertEquals(
            SyncProgress(done = 2, total = 7, stage = SyncStage.SENDING),
            SyncProgressMapper.progress(SyncPhase.SENDING.code, 2, 7)
        )
        assertEquals(
            SyncProgress(done = 0, total = 0, stage = SyncStage.CHECKING),
            SyncProgressMapper.progress(SyncPhase.CHECKING.code, 0, 0)
        )
        assertEquals(
            SyncProgress(done = 3, total = 7, stage = SyncStage.DOWNLOADING),
            SyncProgressMapper.progress(SyncPhase.DOWNLOADING.code, 3, 7)
        )
        assertEquals(
            SyncProgress(done = 0, total = 0, stage = SyncStage.SAVING),
            SyncProgressMapper.progress(SyncPhase.SAVING.code, 0, 0)
        )
    }

    @Test fun no_phase_or_an_unknown_one_means_no_progress() {
        assertNull(SyncProgressMapper.progress(null, 0, 0))
        assertNull(SyncProgressMapper.progress("uploading", 1, 2))
    }
}
