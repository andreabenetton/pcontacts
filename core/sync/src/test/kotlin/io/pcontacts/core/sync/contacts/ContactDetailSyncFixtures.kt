// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.sync.contacts

import android.accounts.Account
import io.pcontacts.core.contactswriter.ApplyResult
import io.pcontacts.core.contactswriter.ExistingRawContact
import io.pcontacts.core.contactswriter.ExistingRawContacts
import io.pcontacts.core.contactswriter.RawContactOpIntent
import io.pcontacts.core.proton.api.contacts.BulkDeleteRequest
import io.pcontacts.core.proton.api.contacts.BulkDeleteResponse
import io.pcontacts.core.proton.api.contacts.ContactCardDto
import io.pcontacts.core.proton.api.contacts.ContactDto
import io.pcontacts.core.proton.api.contacts.ContactEmailsPageResponse
import io.pcontacts.core.proton.api.contacts.ContactMetadataDto
import io.pcontacts.core.proton.api.contacts.ContactsMetadataPager
import io.pcontacts.core.proton.api.contacts.ContactsPageResponse
import io.pcontacts.core.proton.api.contacts.CreateContactsRequest
import io.pcontacts.core.proton.api.contacts.CreateContactsResponse
import io.pcontacts.core.proton.api.contacts.GetContactResponse
import io.pcontacts.core.proton.api.contacts.ProtonContactsApi
import io.pcontacts.core.proton.api.contacts.UpdateContactRequest
import io.pcontacts.core.proton.api.contacts.UpdateContactResponse
import io.pcontacts.core.proton.api.labels.GetLabelsResponse
import io.pcontacts.core.proton.api.labels.ProtonLabelsApi
import io.pcontacts.core.protoncontacts.ContactDecrypter
import io.pcontacts.core.protoncontacts.ContactProcessor
import io.pcontacts.core.storage.db.dao.ContactMapDao
import io.pcontacts.core.storage.db.entity.ContactMapEntity

/**
 * Shared fixtures for the ContactDetailSyncEngine test suites
 * (ContactDetailSyncEngineTest, ContactDetailSyncEngineSelfHealingTest).
 * Uses CLEAR_TEXT cards so the real ContactProcessor exercises actual
 * ez-vcard parsing without needing real PGP keys.
 */
internal val aliceVCard = """
    BEGIN:VCARD
    VERSION:4.0
    FN:Alice
    EMAIL:alice@proton.me
    END:VCARD
""".trimIndent()

internal fun meta(id: String, modifyTime: Long) = ContactMetadataDto(id = id, modifyTime = modifyTime)

internal fun metaPage(vararg rows: ContactMetadataDto) =
    ContactsPageResponse(code = 1000, contacts = rows.toList(), total = rows.size)

internal fun contact(id: String, modifyTime: Long, clearTextVCard: String) = ContactDto(
    id = id,
    modifyTime = modifyTime,
    cards = listOf(ContactCardDto(type = 0, data = clearTextVCard))
)

internal fun newEngine(
    api: DetailFakeApi,
    dao: DetailFakeContactMapDao,
    applier: DetailFakeApplier,
    hasPendingDelete: suspend (String) -> Boolean = { false }
): ContactDetailSyncEngine {
    val processor = ContactProcessor(
        ContactDecrypter(cryptoOp = { _ ->
            error("CLEAR_TEXT-only cards must not invoke crypto op")
        })
    )
    return ContactDetailSyncEngine(
        metadataPager = ContactsMetadataPager(api = api, pageSize = 1000),
        contactsApi = api,
        labelsApi = NoLabelsApi,
        processor = processor,
        contactMapDao = dao,
        readExisting = { _ -> applier.knownState() },
        hasPendingDelete = hasPendingDelete,
        applyIntents = { acct, intents -> applier.apply(acct, intents) },
        clock = { 1_700_000_000L }
    )
}

/** Returns an empty label set for engine tests that don't care about groups. */
internal object NoLabelsApi : ProtonLabelsApi {
    override suspend fun listLabels(type: Int): GetLabelsResponse =
        GetLabelsResponse(code = 1000, labels = emptyList())
}

/**
 * Serves the two endpoints the detail engine uses: listContacts and getContact.
 * `secondRoundContacts` swaps the contact map after the first round; the
 * "round" boundary fires when every contact in the initial map has been
 * fetched at least once.
 */
internal class DetailFakeApi(
    metadataPages: List<ContactsPageResponse>,
    private val contacts: Map<String, ContactDto>,
    private val secondRoundContacts: Map<String, ContactDto>? = null,
    private val repeatContacts: Boolean = false
) : ProtonContactsApi {
    private val metadataQueue = ArrayDeque(metadataPages)
    private var firstRoundDone = false
    private val firstRoundFetched = HashSet<String>()
    var getContactCallCount = 0
        private set

    override suspend fun listContacts(
        page: Int,
        pageSize: Int,
        labelIdFilter: String?
    ): ContactsPageResponse =
        if (metadataQueue.isEmpty()) ContactsPageResponse(code = 1000) else metadataQueue.removeFirst()

    override suspend fun listContactEmails(
        page: Int,
        pageSize: Int,
        emailFilter: String?,
        labelIdFilter: String?
    ): ContactEmailsPageResponse =
        error("ContactDetailSyncEngine does not use /emails")

    override suspend fun getContact(id: String): GetContactResponse {
        getContactCallCount += 1
        val source = when {
            firstRoundDone && secondRoundContacts != null -> secondRoundContacts
            else -> contacts
        }
        val contact = source[id] ?: error("DetailFakeApi has no fixture for contact id=$id")

        if (!firstRoundDone) {
            firstRoundFetched += id
            if (firstRoundFetched.size == contacts.size) {
                firstRoundDone = true
                if (repeatContacts) firstRoundFetched.clear()
            }
        }
        return GetContactResponse(code = 1000, contact = contact)
    }

    override suspend fun createContacts(request: CreateContactsRequest): CreateContactsResponse =
        error("not used in read-engine tests")

    override suspend fun updateContact(id: String, request: UpdateContactRequest): UpdateContactResponse =
        error("not used in read-engine tests")

    override suspend fun deleteContacts(request: BulkDeleteRequest): BulkDeleteResponse =
        error("not used in read-engine tests")
}

internal class DetailFakeContactMapDao : ContactMapDao {
    private val rows = HashMap<String, ContactMapEntity>()
    fun snapshot(): Map<String, ContactMapEntity> = rows.toMap()
    override suspend fun upsert(entry: ContactMapEntity) { rows[entry.protonContactId] = entry }
    override suspend fun upsertAll(entries: List<ContactMapEntity>) {
        entries.forEach { rows[it.protonContactId] = it }
    }
    override suspend fun findByProtonId(id: String) = rows[id]
    override suspend fun findByRawContactId(rawId: Long) =
        rows.values.firstOrNull { it.androidRawContactId == rawId }
    override suspend fun findByProtonUid(uid: String) =
        rows.values.firstOrNull { it.protonUid == uid }
    override suspend fun listLiveProtonIds(): List<String> =
        rows.values.filter { !it.deleted }.map { it.protonContactId }
    override suspend fun listLive(): List<ContactMapEntity> =
        rows.values.filter { !it.deleted }
    override suspend fun countLive(): Int =
        rows.values.count { !it.deleted }
    override suspend fun countUnverified(): Int =
        rows.values.count { !it.deleted && !it.isVerified }
    override suspend fun listUnverified(): List<ContactMapEntity> =
        rows.values.filter { !it.deleted && !it.isVerified }
    override suspend fun markDeleted(id: String) {
        rows[id]?.let { rows[id] = it.copy(deleted = true) }
    }
    override suspend fun listConflicts(): List<ContactMapEntity> =
        rows.values.filter { it.syncStatus == ContactMapEntity.Status.CONFLICT && !it.deleted }
    override suspend fun resolveConflict(id: String) {
        rows[id]?.let { rows[id] = it.copy(syncStatus = ContactMapEntity.Status.CLEAN, lastError = null) }
    }
    override suspend fun maxLastSyncedAt(): Long? =
        rows.values.filter { !it.deleted }.maxOfOrNull { it.lastSyncedAt }
    override suspend fun deleteByProtonId(id: String) { rows.remove(id) }
    override suspend fun deleteAll() { rows.clear() }
}

internal class DetailFakeApplier(base: Long) {
    private val rows = HashMap<String, MutableList<ExistingRawContact>>()
    private var nextId = base
    var applyCallCount = 0
        private set
    var lastIntents: List<RawContactOpIntent> = emptyList()
        private set

    fun knownState(): ExistingRawContacts =
        ExistingRawContacts(rows.mapValues { (_, list) -> list.toList() })

    fun rawIdsFor(sourceId: String): List<Long> =
        rows[sourceId].orEmpty().map { it.rawContactId }

    /** Simulates an external app purging our row outright (no tombstone). */
    fun removeRawContact(sourceId: String) {
        rows.remove(sourceId)
    }

    /** Seeds provider state directly — duplicates, tombstones, foreign recreations. */
    fun seedRow(sourceId: String, rawId: Long, deleted: Boolean = false) {
        rows.getOrPut(sourceId) { ArrayList() } += ExistingRawContact(rawId, deleted)
    }

    // Signature mirrors the applyIntents seam; the fake ignores the account.
    @Suppress("UnusedParameter")
    suspend fun apply(account: Account, intents: List<RawContactOpIntent>): ApplyResult {
        applyCallCount += 1
        lastIntents = intents
        for (intent in intents) when (intent) {
            is RawContactOpIntent.CreateContact -> seedRow(intent.row.sourceId, nextId++)
            is RawContactOpIntent.DeleteContact -> rows.remove(intent.sourceId)
            is RawContactOpIntent.UpdateContact -> { /* id unchanged */ }
            is RawContactOpIntent.DeleteRawContact -> {
                rows.values.forEach { list -> list.removeAll { it.rawContactId == intent.rawContactId } }
                rows.entries.removeAll { it.value.isEmpty() }
            }
        }
        return ApplyResult(
            insertedContacts = intents.count { it is RawContactOpIntent.CreateContact },
            updatedContacts = intents.count { it is RawContactOpIntent.UpdateContact },
            deletedContacts = intents.count { it is RawContactOpIntent.DeleteContact }
        )
    }
}
