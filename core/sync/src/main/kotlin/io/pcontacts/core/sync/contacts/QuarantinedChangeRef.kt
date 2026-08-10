// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.sync.contacts

import io.pcontacts.core.storage.db.dao.ContactMapDao
import io.pcontacts.core.storage.db.dao.OutboxDao
import io.pcontacts.core.storage.db.entity.OutboxEntity

/**
 * The outbound operation a quarantined outbox entry was carrying.
 * Null when the stored `op_type` is not one this build understands —
 * [ContactWriteEngine] quarantines such rows rather than guessing, so
 * the UI must be able to render them too.
 */
enum class ChangeOp { CREATE, UPDATE, DELETE }

/**
 * Lightweight reference to a local edit that failed permanently and was
 * side-lined into the outbox quarantine (ADR-0017 §5B). Returned by
 * [SyncBootstrap.listQuarantinedChanges] so the settings UI can show
 * *which* change failed instead of only how many.
 *
 * Carries no display name on purpose, for the same reason as
 * [UnverifiedContactRef]: names live in ContactsContract, not in our
 * Room mapping (ADR-0007). The settings layer resolves the name via
 * ContentResolver using [androidRawContactId] as the join key.
 *
 * [androidRawContactId] is null when the row can no longer be joined —
 * a DELETE whose mapping is already gone, or a CREATE whose local raw
 * contact the user removed since. The UI falls back to the operation
 * label and [lastError] in that case.
 *
 * [lastError] is the reason [OutboxDao.quarantine] persisted: an
 * exception class name plus HTTP code, or a short internal reason. It
 * never contains decrypted contact content (ADR-0007).
 */
data class QuarantinedChangeRef(
    val outboxId: Long,
    val protonContactId: String,
    val androidRawContactId: Long?,
    val op: ChangeOp?,
    val lastError: String?,
    val createdAt: Long
)

/**
 * Prefix [ContactWriteEngine] uses for the synthetic contact id of a
 * CREATE that has not reached Proton yet — the real Proton id does not
 * exist until the server assigns one, so the raw contact id is encoded
 * into the placeholder.
 */
private const val LOCAL_ID_PREFIX = "local-"

/**
 * Builds the quarantine view for the settings UI. Split out of
 * [SyncBootstrap] so it can be tested against fake DAOs without an
 * Android [android.content.Context].
 */
internal suspend fun buildQuarantinedChangeRefs(
    outboxDao: OutboxDao,
    contactMapDao: ContactMapDao
): List<QuarantinedChangeRef> = outboxDao.listQuarantined().map { entry ->
    QuarantinedChangeRef(
        outboxId = entry.id,
        protonContactId = entry.protonContactId,
        androidRawContactId = resolveRawContactId(entry.protonContactId, contactMapDao),
        op = entry.opType.toChangeOp(),
        lastError = entry.lastError,
        createdAt = entry.createdAt
    )
}

private suspend fun resolveRawContactId(
    protonContactId: String,
    contactMapDao: ContactMapDao
): Long? = if (protonContactId.startsWith(LOCAL_ID_PREFIX)) {
    protonContactId.removePrefix(LOCAL_ID_PREFIX).toLongOrNull()
} else {
    contactMapDao.findByProtonId(protonContactId)?.androidRawContactId
}

private fun Int.toChangeOp(): ChangeOp? = when (this) {
    OutboxEntity.OpType.CREATE -> ChangeOp.CREATE
    OutboxEntity.OpType.UPDATE -> ChangeOp.UPDATE
    OutboxEntity.OpType.DELETE -> ChangeOp.DELETE
    else -> null
}
