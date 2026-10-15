// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.sync.contacts

import io.pcontacts.core.protoncontacts.ProtonAdjustment
import io.pcontacts.core.storage.db.dao.ContactMapDao
import io.pcontacts.core.storage.db.dao.ProtonAdjustmentDao

/**
 * A value conformed to Proton's editor rules before it was sent (ADR-0017, 2026-10-15, third),
 * for the Settings list. No name or value, as for [QuarantinedChangeRef]: the settings layer
 * reads the name from ContactsContract by [androidRawContactId], null once the contact is gone.
 */
data class ProtonAdjustmentRef(
    val protonContactId: String,
    val androidRawContactId: Long?,
    val adjustment: ProtonAdjustment,
    val createdAt: Long
)

/** The stored adjustments, newest first; a kind this build does not know is left out. */
internal suspend fun buildProtonAdjustmentRefs(
    adjustmentDao: ProtonAdjustmentDao,
    contactMapDao: ContactMapDao
): List<ProtonAdjustmentRef> = adjustmentDao.listAll().mapNotNull { row ->
    ProtonAdjustment.fromKey(row.kind)?.let { adjustment ->
        ProtonAdjustmentRef(
            protonContactId = row.protonContactId,
            androidRawContactId = contactMapDao.findByProtonId(row.protonContactId)?.androidRawContactId,
            adjustment = adjustment,
            createdAt = row.createdAt
        )
    }
}
