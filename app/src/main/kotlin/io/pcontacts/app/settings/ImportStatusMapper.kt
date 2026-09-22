// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.app.settings

import io.pcontacts.core.storage.db.entity.ContactMapEntity
import io.pcontacts.core.storage.db.entity.OutboxEntity
import io.pcontacts.feature.settings.ImportStatus

/** The import list's status of a contact, derived from its mapping and outbox rows alone. */
internal object ImportStatusMapper {

    fun status(mapping: ContactMapEntity, outboxRows: List<OutboxEntity>): ImportStatus = when {
        outboxRows.any { it.quarantined } -> ImportStatus.FAILED
        outboxRows.any { !it.quarantined } -> ImportStatus.QUEUED
        mapping.syncStatus == ContactMapEntity.Status.CONFLICT -> ImportStatus.FAILED
        mapping.protonContactId.startsWith("local-") -> ImportStatus.QUEUED
        else -> ImportStatus.SYNCED
    }
}
