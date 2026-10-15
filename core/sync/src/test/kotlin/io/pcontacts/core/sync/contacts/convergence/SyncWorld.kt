// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.sync.contacts.convergence

import android.accounts.Account
import io.pcontacts.core.contactswriter.ContactRow
import io.pcontacts.core.contactswriter.PhoneEntry
import io.pcontacts.core.contactswriter.PhoneType
import io.pcontacts.core.logging.RedactingLogger
import io.pcontacts.core.proton.api.contacts.ContactDto
import io.pcontacts.core.proton.api.contacts.ContactsMetadataPager
import io.pcontacts.core.protoncontacts.CardCryptoOutcome
import io.pcontacts.core.protoncontacts.CardCryptoRequest
import io.pcontacts.core.protoncontacts.CardEncryptOutcome
import io.pcontacts.core.protoncontacts.CardEncryptRequest
import io.pcontacts.core.protoncontacts.ContactDecrypter
import io.pcontacts.core.protoncontacts.ContactProcessor
import io.pcontacts.core.protoncontacts.ContactSerializer
import io.pcontacts.core.storage.KeystoreMergeBaseStore
import io.pcontacts.core.storage.SecretCipher
import io.pcontacts.core.storage.db.entity.ContactMapEntity
import io.pcontacts.core.storage.db.entity.OutboxEntity
import io.pcontacts.core.sync.contacts.ContactDetailSyncEngine
import io.pcontacts.core.sync.contacts.ContactWriteEngine
import io.pcontacts.core.sync.contacts.DecryptedContactToRow
import io.pcontacts.core.sync.contacts.DetailFakeContactMapDao
import io.pcontacts.core.sync.contacts.NoLabelsApi
import io.pcontacts.core.sync.contacts.RowToDecryptedContact
import io.pcontacts.core.sync.contacts.SERVER_EDITED_CONFLICT
import io.pcontacts.core.sync.contacts.WriteFakeOutboxDao
import io.pcontacts.core.sync.contacts.cancelPendingDelete
import io.pcontacts.core.sync.contacts.discardQuarantinedChange
import io.pcontacts.core.sync.contacts.merge.MergeBaseCodec
import io.pcontacts.core.sync.contacts.resolveConflict
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * One phone, one Proton account and the user editing both, driven through the real write and
 * pull engines exactly as `ProtonSyncAdapter` runs them (detect, push, pull), with the wiring of
 * `SyncBootstrap` repeated here over the fakes. Every contact carries a marker website, its
 * identity across both sides.
 *
 * Rows are kept in the form the round trip produces (row → cards → row), so a difference is
 * the sync's doing, never a projection quirk; the self-test checks that form is stable.
 */
// The world is the harness: its pieces share one seeded state on purpose.
@Suppress("TooManyFunctions")
internal class SyncWorld(seed: Long) {
    val rng = Random(seed)
    val faults = Faults(rng)
    val server = FakeProtonServer(faults, rng)
    val provider = FakeContactsProvider(faults, rng)
    private val outboxStore = WriteFakeOutboxDao()
    private val mapStore = DetailFakeContactMapDao()
    val outbox = FaultyOutboxDao(outboxStore, faults)
    val contactMap = FaultyContactMapDao(mapStore, faults)
    private val mergeBases = KeystoreMergeBaseStore(contactMap, IdentityCipher)
    var now = START_MS
    val logs = ArrayDeque<String>()
    private val logger = RedactingLogger(tag = "Convergence", sink = { level, tag, msg, _ -> log("$level $tag $msg") })
    private val account = Account("fuzz@proton.me", "io.pcontacts")

    val serializer = ContactSerializer(
        encryptOp = { request ->
            when (request) {
                is CardEncryptRequest.SignOnly -> CardEncryptOutcome(data = request.plaintext, signature = "sig")
                is CardEncryptRequest.EncryptAndSign -> CardEncryptOutcome(data = request.plaintext, signature = "sig")
            }
        }
    )
    val processor = ContactProcessor(
        ContactDecrypter(
            cryptoOp = { request ->
                when (request) {
                    is CardCryptoRequest.VerifyOnly -> CardCryptoOutcome(request.data, verified = true)
                    is CardCryptoRequest.DecryptAndVerify -> CardCryptoOutcome(request.armored, verified = true)
                    is CardCryptoRequest.DecryptOnly -> CardCryptoOutcome(request.armored, verified = false)
                }
            }
        )
    )

    private fun log(line: String) {
        logs.addLast(line)
        if (logs.size > LOG_SIZE) logs.removeFirst()
    }

    // ---- the engines, wired as SyncBootstrap wires them ----

    private fun writeEngine() = ContactWriteEngine(
        contactsApi = server,
        serializer = serializer,
        outboxDao = outbox,
        contactMapDao = contactMap,
        mergeBases = mergeBases,
        readDirtyContacts = { provider.readDirty() },
        hasLiveRow = { _, id -> provider.hasLiveRow(id) },
        liveRawId = { _, id -> provider.liveRawId(id) },
        readContactRow = { rawId, sourceId -> provider.readRow(rawId, sourceId) },
        clearDirtyFlag = { _, rawId -> provider.clearDirty(rawId) },
        writeSourceId = { _, rawId, id -> provider.writeSourceId(rawId, id) },
        fetchServerContact = { id -> processor.process(server.getContact(id).contact) },
        clock = { now },
        logger = logger
    )

    private fun readEngine() = ContactDetailSyncEngine(
        metadataPager = ContactsMetadataPager(server, pageSize = PAGE_SIZE),
        contactsApi = server,
        labelsApi = NoLabelsApi,
        processor = processor,
        contactMapDao = contactMap,
        readExisting = { provider.readExisting() },
        applyIntents = { _, intents -> provider.apply(intents) },
        hasPendingDelete = { id ->
            outbox.findByContact(id).any { !it.quarantined && it.opType == OutboxEntity.OpType.DELETE }
        },
        hasLiveOutboxRow = { id -> outbox.findLive(id) != null },
        saveMergeBase = { id, contact -> MergeBaseCodec.save(mergeBases, id, contact) },
        hasLocalMutation = { id -> outbox.findByContact(id).isNotEmpty() },
        readLocalRow = { rawId, sourceId -> provider.readRow(rawId, sourceId) },
        readDirtyRawIds = { provider.readDirty().map { it.rawContactId }.toSet() },
        queueUpdate = { id -> outbox.enqueue(id, OutboxEntity.OpType.UPDATE, "", now) },
        dropQueuedDelete = { id ->
            outbox.findLive(id)?.takeIf { it.opType == OutboxEntity.OpType.DELETE }?.let { outbox.deleteById(it.id) }
        },
        clock = { now },
        logger = logger
    )

    /** One sync run; returns what escaped it (null when it completed). */
    suspend fun runOnce(scope: CoroutineScope, withFaults: Boolean): Throwable? {
        server.engineMutations.clear()
        provider.engineWrites.clear()
        var failure: Throwable? = null
        val write = writeEngine()
        val read = readEngine()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                write.detectChanges(account)
                write.push(account)
                read.sync(account)
            } catch (t: Throwable) {
                failure = t
            }
        }
        faults.startRun(job)
        faults.enabled = withFaults
        faults.note("== run start")
        job.start()
        job.join()
        faults.note("== run end")
        faults.enabled = false
        faults.endRun()
        return failure ?: CancellationException("run cancelled").takeIf { job.isCancelled }
    }

    val mutated: Boolean get() = server.engineMutations.isNotEmpty() || provider.engineWrites.isNotEmpty()

    // ---- the user's answers (Settings), as SyncBootstrap gives them ----

    /** The conflicts a run starts with, and those of them the phone changed again since. */
    suspend fun waiting(): Waiting {
        val ids = conflictIds()
        val dirty = provider.rows.values.filter { it.dirty }.mapNotNull { it.sourceId }
        val queued = ids.filter { outbox.findLive(it) != null }
        return Waiting(ids, ids intersect (dirty + queued).toSet())
    }

    suspend fun conflictIds(): Set<String> =
        mapStore.listConflicts().map { it.protonContactId }.toSet()

    suspend fun answerQuestions(all: Boolean) {
        for (id in conflictIds()) {
            if (!all && rng.nextInt(QUESTION_ODDS) != 0) continue
            mapStore.findByProtonId(id)?.let { mapping -> contended += markerOfMapping(mapping) }
            val deletedHere = mapStore.findByProtonId(id)?.lastError == SERVER_EDITED_CONFLICT
            val useLocal = rng.nextBoolean()
            faults.note("user answers $id ${mapStore.findByProtonId(id)?.lastError} useLocal=$useLocal")
            resolveConflict(contactMap, outbox, id, useLocal, now)
            if (deletedHere && !useLocal) {
                cancelPendingDelete(contactMap, outbox, id) { provider.restoreTombstones(it) }
            }
        }
        for (failed in outboxStore.listQuarantined()) {
            if (!all && rng.nextInt(QUESTION_ODDS) != 0) continue
            if (retried.add(failed.id)) {
                outbox.requeue(failed.id)
            } else {
                // Retried and refused again: the user discards it, as Settings offers. A change
                // still carrying an edit leaves the sides apart, which L3 reports.
                discarded += failed
                discardQuarantinedChange(contactMap, outbox, failed.id) { provider.restoreTombstones(it) }
            }
        }
    }

    private val retried = mutableSetOf<Long>()

    /** Failed changes the user had to discard: each is a change the sync could not settle by itself. */
    val discarded = mutableListOf<OutboxEntity>()

    private fun markerOfMapping(mapping: ContactMapEntity): String =
        provider.rows[mapping.androidRawContactId]?.data?.let(::markerOf)
            ?: server.contacts[mapping.protonContactId]?.let { markerOf(serverRow(it)) }
            ?: "unknown"

    // ---- the round-trip form and the two sides as rows ----

    fun normalize(row: ContactRow): ContactRow {
        val contact = RowToDecryptedContact.convert(row.copy(sourceId = "n"), "n", "urn:uuid:n")
        return project(ContactDto(id = "n", uid = "urn:uuid:n", cards = serializer.serialize(contact)))
    }

    fun serverRow(stored: FakeProtonServer.Stored): ContactRow =
        project(ContactDto(id = stored.id, uid = stored.uid, cards = stored.cards))

    private fun project(dto: ContactDto): ContactRow =
        DecryptedContactToRow.convert(processor.process(dto))!!.copy(sourceId = "", groupRowIds = emptyList())

    fun markerOf(row: ContactRow): String? = row.websites.firstOrNull { it.startsWith(MARKER) }

    fun liveLocalRows(): List<FakeContactsProvider.Raw> = provider.rows.values.filter { !it.deleted }

    // ---- the users' edits, between runs, applied to a side and to the oracle ----

    private var nextMarker = 0
    val markers = mutableSetOf<String>()
    val deleted = mutableSetOf<String>()

    /** What each contact should end as, when only one side touched it this epoch; null: deleted. */
    val expected = mutableMapOf<String, ContactRow?>()
    val contended = mutableSetOf<String>()
    private val touchedLocally = mutableSetOf<String>()
    private val touchedOnServer = mutableSetOf<String>()

    fun startEpoch() {
        contended.clear()
        touchedLocally.clear()
        touchedOnServer.clear()
    }

    private fun touch(marker: String, local: Boolean) {
        val (mine, theirs) = if (local) touchedLocally to touchedOnServer else touchedOnServer to touchedLocally
        mine += marker
        if (marker in theirs) contended += marker
    }

    fun newRow(): ContactRow {
        val n = nextMarker++
        return normalize(
            ContactRow(
                sourceId = "",
                displayName = "Contact $n",
                emails = listOf("c$n@x.test"),
                phones = listOf(PhoneEntry(number(), phoneType())),
                websites = listOf("$MARKER$n")
            )
        )
    }

    private fun number() = "+155501${rng.nextInt(NUMBERS)}"

    private fun phoneType() = PHONE_TYPES[rng.nextInt(PHONE_TYPES.size)]

    /** One edit; same number with another type is drawn on purpose (ADR-0017, 2026-10-14). */
    fun edited(row: ContactRow): ContactRow {
        val next = when (rng.nextInt(EDIT_KINDS)) {
            0 -> row.copy(displayName = "Name ${rng.nextInt(BOUND)}")
            1 -> row.copy(notes = if (rng.nextBoolean()) listOf("note ${rng.nextInt(BOUND)}") else emptyList())
            2 -> row.copy(emails = (row.emails + "e${rng.nextInt(NUMBERS)}@x.test").distinct())
            3 -> row.copy(emails = row.emails.drop(1))
            4 -> row.copy(phones = (row.phones + PhoneEntry(number(), phoneType())).distinct())
            5 -> row.copy(phones = row.phones.dropLast(1))
            else -> row.copy(phones = row.phones.mapIndexed { i, p -> if (i == 0) p.copy(type = phoneType()) else p })
        }
        return normalize(next)
    }

    fun localEdit() {
        val live = liveLocalRows().filter { markerOf(it.data) != null }
        val roll = rng.nextInt(ODDS)
        when {
            roll < CREATE_ODDS || live.isEmpty() -> {
                val row = newRow()
                provider.userCreate(row)
                record(markerOf(row)!!, row, local = true)
            }
            roll < CREATE_ODDS + DELETE_ODDS -> {
                val victim = live[rng.nextInt(live.size)]
                provider.userDelete(victim.id)
                record(markerOf(victim.data)!!, null, local = true)
            }
            else -> {
                val target = live[rng.nextInt(live.size)]
                val row = edited(target.data)
                provider.userEdit(target.id, row)
                record(markerOf(row)!!, row, local = true)
            }
        }
    }

    fun serverEdit() {
        val all = server.contacts.values.toList()
        val roll = rng.nextInt(ODDS)
        when {
            roll < CREATE_ODDS || all.isEmpty() -> {
                val row = newRow()
                val marker = markerOf(row)!!
                server.webCreate("urn:uuid:web-$marker", cards(row, "new", "urn:uuid:web-$marker"))
                record(marker, row, local = false)
            }
            roll < CREATE_ODDS + DELETE_ODDS -> {
                val victim = all[rng.nextInt(all.size)]
                record(markerOf(serverRow(victim))!!, null, local = false)
                server.webDelete(victim.id)
            }
            else -> {
                val target = all[rng.nextInt(all.size)]
                val row = edited(serverRow(target))
                server.webEdit(target.id, cards(row, target.id, target.uid))
                record(markerOf(row)!!, row, local = false)
            }
        }
    }

    /** The web client writes the whole card anew. */
    private fun cards(row: ContactRow, id: String, uid: String) =
        serializer.serialize(RowToDecryptedContact.convert(row.copy(sourceId = id), id, uid))

    private fun record(marker: String, row: ContactRow?, local: Boolean) {
        faults.note("user ${if (local) "phone" else "web"} ${if (row == null) "delete" else "edit"} $marker $row")
        markers += marker
        if (row == null) deleted += marker
        expected[marker] = row
        touch(marker, local)
    }

    /** IDs of the contacts a run must leave alone: a conflict waits for the user (ADR-0017 §3C). */
    fun touchedIds(): Set<String> =
        server.engineMutations.map { it.second }.toSet() +
            provider.engineWrites.filter { it.first == "update" || it.first == "delete" }.map { it.second }

    private object IdentityCipher : SecretCipher {
        override fun wrap(plaintext: ByteArray): ByteArray = plaintext.copyOf()
        override fun unwrap(wrapped: ByteArray): ByteArray = wrapped.copyOf()
        override fun delete() = Unit
    }

    companion object {
        const val MARKER = "https://lid.test/"
        const val START_MS = 1_760_000_000_000L
        const val HOUR_MS = 3_600_000L
        private const val PAGE_SIZE = 4
        private const val LOG_SIZE = 200000
        private const val QUESTION_ODDS = 3
        private const val NUMBERS = 4
        private const val BOUND = 100
        private const val EDIT_KINDS = 7
        private const val ODDS = 20
        private const val CREATE_ODDS = 3
        private const val DELETE_ODDS = 2
        private val PHONE_TYPES = listOf(PhoneType.HOME, PhoneType.WORK, PhoneType.MOBILE, PhoneType.OTHER)
    }
}

/** Contacts awaiting the user's answer when a run starts; [editedOnPhone] may settle through the merge. */
internal data class Waiting(val ids: Set<String>, val editedOnPhone: Set<String>)
