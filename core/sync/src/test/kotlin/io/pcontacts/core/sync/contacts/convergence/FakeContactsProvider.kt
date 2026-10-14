// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.sync.contacts.convergence

import io.pcontacts.core.contactswriter.ApplyResult
import io.pcontacts.core.contactswriter.ContactRow
import io.pcontacts.core.contactswriter.DirtyContact
import io.pcontacts.core.contactswriter.ExistingRawContact
import io.pcontacts.core.contactswriter.ExistingRawContacts
import io.pcontacts.core.contactswriter.RawContactOpIntent
import kotlin.random.Random

/**
 * The account's RawContacts as ContactsProvider holds them, with the behaviour the engines
 * rely on. `[A]` modelled, not observed here: a sync-adapter write leaves DIRTY alone; a user
 * delete leaves a tombstone that keeps its data; a sync-adapter delete purges the rows of a
 * SOURCE_ID, tombstones included; raw ids are never reused; a batch commits chunk by chunk.
 */
internal class FakeContactsProvider(private val faults: Faults, private val rng: Random) {

    class Raw(val id: Long, var sourceId: String?, var dirty: Boolean, var deleted: Boolean, var data: ContactRow)

    val rows = LinkedHashMap<Long, Raw>()

    /** Writes the engines committed in the current run, as (operation, SOURCE_ID or raw id). */
    val engineWrites = mutableListOf<Pair<String, String>>()
    private var nextId = 100L

    // ---- sync-adapter side: the engine seams ----

    suspend fun readExisting(): ExistingRawContacts = faults.checkpoint(Site.PROVIDER, "read rows") {
        ExistingRawContacts(
            rows.values.filter { it.sourceId != null }
                .groupBy({ it.sourceId!! }) { ExistingRawContact(it.id, it.deleted) }
        )
    }

    /** Commits in chunks of one to three intents: a stop between chunks leaves the earlier ones written. */
    suspend fun apply(intents: List<RawContactOpIntent>): ApplyResult {
        var rest = intents
        while (rest.isNotEmpty()) {
            val chunk = rest.take(rng.nextInt(1, MAX_CHUNK + 1))
            rest = rest.drop(chunk.size)
            faults.checkpoint(Site.PROVIDER, "apply ${chunk.size}") { chunk.forEach(::applyOne) }
        }
        return ApplyResult(
            insertedContacts = intents.count { it is RawContactOpIntent.CreateContact },
            updatedContacts = intents.count { it is RawContactOpIntent.UpdateContact },
            deletedContacts = intents.count { it is RawContactOpIntent.DeleteContact }
        )
    }

    private fun applyOne(intent: RawContactOpIntent) {
        when (intent) {
            is RawContactOpIntent.CreateContact -> {
                val id = nextId++
                rows[id] = Raw(id, intent.row.sourceId, dirty = false, deleted = false, data = intent.row.plain())
                engineWrites += "create" to intent.row.sourceId
            }
            is RawContactOpIntent.UpdateContact -> rows[intent.rawContactId]?.let {
                it.data = intent.row.plain()
                engineWrites += "update" to (it.sourceId ?: "raw-${it.id}")
            }
            is RawContactOpIntent.DeleteContact -> {
                rows.values.removeAll { it.sourceId == intent.sourceId }
                engineWrites += "delete" to intent.sourceId
            }
            is RawContactOpIntent.DeleteRawContact -> rows.remove(intent.rawContactId)?.let {
                engineWrites += "delete-raw" to (it.sourceId ?: "raw-${it.id}")
            }
        }
    }

    suspend fun readDirty(): List<DirtyContact> = faults.checkpoint(Site.PROVIDER, "read dirty") {
        rows.values.filter { it.dirty || it.deleted }.map { DirtyContact(it.id, it.sourceId, it.dirty, it.deleted) }
    }

    suspend fun readRow(rawId: Long, sourceId: String): ContactRow? = faults.checkpoint(Site.PROVIDER, "read $rawId") {
        rows[rawId]?.data?.copy(sourceId = sourceId)
    }

    suspend fun clearDirty(rawId: Long) = faults.checkpoint(Site.PROVIDER, "clear dirty $rawId") {
        rows[rawId]?.dirty = false
    }

    suspend fun writeSourceId(rawId: Long, sourceId: String) = faults.checkpoint(Site.PROVIDER, "source id $rawId") {
        rows[rawId]?.sourceId = sourceId
    }

    suspend fun hasLiveRow(sourceId: String): Boolean = faults.checkpoint(Site.PROVIDER, "live $sourceId") {
        rows.values.any { it.sourceId == sourceId && !it.deleted }
    }

    /** Puts a tombstoned contact back (the user cancelled the deletion); the number of rows restored. */
    fun restoreTombstones(sourceId: String): Int {
        val tombstones = rows.values.filter { it.sourceId == sourceId && it.deleted }
        tombstones.forEach {
            it.deleted = false
            it.dirty = false
        }
        return tombstones.size
    }

    // ---- the phone's user, between runs ----

    fun userCreate(data: ContactRow): Long {
        val id = nextId++
        rows[id] = Raw(id, sourceId = null, dirty = true, deleted = false, data = data.plain())
        return id
    }

    fun userEdit(rawId: Long, data: ContactRow) {
        rows.getValue(rawId).apply {
            this.data = data.plain()
            dirty = true
        }
    }

    fun userDelete(rawId: Long) {
        rows.getValue(rawId).apply {
            deleted = true
            dirty = true
        }
    }

    /** The row as the provider stores it: its SOURCE_ID lives on the RawContact, not in the data. */
    private fun ContactRow.plain() = copy(sourceId = "", groupRowIds = emptyList())

    private companion object {
        const val MAX_CHUNK = 3
    }
}
