// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.app.settings

import io.pcontacts.core.storage.db.entity.ContactMapEntity
import io.pcontacts.core.storage.db.entity.OutboxEntity
import io.pcontacts.feature.settings.ImportStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class ImportStatusMapperTest {

    private fun mapping(id: String, status: Int = ContactMapEntity.Status.CLEAN) = ContactMapEntity(
        protonContactId = id,
        protonUid = null,
        androidRawContactId = 1L,
        modifyTime = 0L,
        contentHash = "",
        isVerified = true,
        deleted = false,
        syncStatus = status,
        lastError = null,
        lastSyncedAt = 0L
    )

    private fun row(quarantined: Boolean) = OutboxEntity(
        protonContactId = "x",
        opType = OutboxEntity.OpType.CREATE,
        payloadHash = "h",
        createdAt = 1L,
        quarantined = quarantined
    )

    @Test fun a_live_outbox_row_is_queued_and_a_quarantined_one_failed() {
        assertEquals(ImportStatus.QUEUED, ImportStatusMapper.status(mapping("local-1"), listOf(row(false))))
        assertEquals(ImportStatus.FAILED, ImportStatusMapper.status(mapping("local-1"), listOf(row(true))))
    }

    @Test fun without_outbox_rows_a_server_id_means_synced_and_a_local_id_means_still_queued() {
        assertEquals(ImportStatus.SYNCED, ImportStatusMapper.status(mapping("srv-1"), emptyList()))
        assertEquals(ImportStatus.QUEUED, ImportStatusMapper.status(mapping("local-1"), emptyList()))
        assertEquals(
            ImportStatus.FAILED,
            ImportStatusMapper.status(mapping("srv-1", ContactMapEntity.Status.CONFLICT), emptyList())
        )
    }
}
