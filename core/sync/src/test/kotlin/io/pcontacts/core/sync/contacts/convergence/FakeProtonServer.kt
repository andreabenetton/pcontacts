// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.sync.contacts.convergence

import io.pcontacts.core.proton.api.contacts.BulkDeleteRequest
import io.pcontacts.core.proton.api.contacts.BulkDeleteResponse
import io.pcontacts.core.proton.api.contacts.ContactCardDto
import io.pcontacts.core.proton.api.contacts.ContactDto
import io.pcontacts.core.proton.api.contacts.ContactEmailsPageResponse
import io.pcontacts.core.proton.api.contacts.ContactMetadataDto
import io.pcontacts.core.proton.api.contacts.ContactsPageResponse
import io.pcontacts.core.proton.api.contacts.CreateContactResponseBody
import io.pcontacts.core.proton.api.contacts.CreateContactResponseItem
import io.pcontacts.core.proton.api.contacts.CreateContactsRequest
import io.pcontacts.core.proton.api.contacts.CreateContactsResponse
import io.pcontacts.core.proton.api.contacts.DeleteResponseBody
import io.pcontacts.core.proton.api.contacts.DeleteResponseItem
import io.pcontacts.core.proton.api.contacts.GetContactResponse
import io.pcontacts.core.proton.api.contacts.LabelContactEmailsRequest
import io.pcontacts.core.proton.api.contacts.LabelContactEmailsResponse
import io.pcontacts.core.proton.api.contacts.ProtonContactsApi
import io.pcontacts.core.proton.api.contacts.UpdateContactRequest
import io.pcontacts.core.proton.api.contacts.UpdateContactResponse
import io.pcontacts.core.protoncontacts.CardType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.HttpException
import retrofit2.Response
import kotlin.random.Random

/**
 * Proton's contact endpoints over an in-memory store. Cards are plaintext (the harness's
 * crypto passes through), so the UID is read straight from the signed card.
 *
 * `[A]` a missing contact answers HTTP 422 with Code 2501 (seen live 2026-10-01); a second
 * contact with a UID already held is refused with item Code 2500 (seen live 2026-10-14).
 * Groups are out of scope: the label endpoints are never expected.
 */
internal class FakeProtonServer(private val faults: Faults, private val rng: Random) : ProtonContactsApi {

    /** `[U]` paging stability (opt-in): a page that silently leaves one contact out. */
    var omitFromListing = false

    class Stored(val id: String, val uid: String, var cards: List<ContactCardDto>, var modifyTime: Long)

    val contacts = LinkedHashMap<String, Stored>()

    /** Mutations the engines committed in the current run, as (operation, contact id). */
    val engineMutations = mutableListOf<Pair<String, String>>()
    private var nextId = 1
    private var serverClock = 1_000L

    private fun tick(): Long = ++serverClock

    override suspend fun listContacts(page: Int, pageSize: Int, labelIdFilter: String?): ContactsPageResponse =
        faults.checkpoint(Site.LIST, "list p$page") {
            val all = contacts.values.sortedBy { it.id }
            val full = all.drop(page * pageSize).take(pageSize)
            val slice = if (omitFromListing && full.isNotEmpty() && rng.nextInt(OMIT_ODDS) == 0) {
                full - full[rng.nextInt(full.size)]
            } else {
                full
            }
            ContactsPageResponse(
                code = SUCCESS,
                contacts = slice.map { ContactMetadataDto(id = it.id, uid = it.uid, modifyTime = it.modifyTime) },
                total = all.size
            )
        }

    override suspend fun getContact(id: String): GetContactResponse = faults.checkpoint(Site.GET, "get $id") {
        val stored = contacts[id] ?: throw gone()
        GetContactResponse(code = SUCCESS, contact = stored.toDto())
    }

    override suspend fun createContacts(request: CreateContactsRequest): CreateContactsResponse =
        faults.checkpoint(Site.CREATE, "create") {
            val items = request.contacts.mapIndexed { index, bundle ->
                val uid = uidOf(bundle.cards)
                val body = if (contacts.values.any { it.uid == uid }) {
                    CreateContactResponseBody(code = DUPLICATE_UID)
                } else {
                    val stored = store(uid, bundle.cards)
                    engineMutations += "create" to stored.id
                    CreateContactResponseBody(code = SUCCESS, contact = ContactDto(id = stored.id, uid = uid))
                }
                CreateContactResponseItem(index = index, response = body)
            }
            CreateContactsResponse(code = BATCH, responses = items)
        }

    override suspend fun updateContact(id: String, request: UpdateContactRequest): UpdateContactResponse =
        faults.checkpoint(Site.UPDATE, "update $id") {
            val stored = contacts[id] ?: throw gone()
            stored.cards = request.cards
            stored.modifyTime = tick()
            engineMutations += "update" to id
            UpdateContactResponse(code = SUCCESS, contact = stored.toDto())
        }

    override suspend fun deleteContacts(request: BulkDeleteRequest): BulkDeleteResponse =
        faults.checkpoint(Site.DELETE, "delete ${request.ids}") {
            val items = request.ids.map { id ->
                val removed = contacts.remove(id) != null
                if (removed) engineMutations += "delete" to id
                DeleteResponseItem(id = id, response = DeleteResponseBody(code = if (removed) SUCCESS else NOT_EXISTS))
            }
            BulkDeleteResponse(code = BATCH, responses = items)
        }

    override suspend fun listContactEmails(
        page: Int,
        pageSize: Int,
        emailFilter: String?,
        labelIdFilter: String?
    ): ContactEmailsPageResponse = ContactEmailsPageResponse(code = SUCCESS)

    override suspend fun labelContactEmails(request: LabelContactEmailsRequest): LabelContactEmailsResponse =
        error("groups are out of scope for the convergence harness")

    override suspend fun unlabelContactEmails(request: LabelContactEmailsRequest): LabelContactEmailsResponse =
        error("groups are out of scope for the convergence harness")

    // ---- the web user, between runs: never faulted, never counted as the engines' doing ----

    fun webCreate(uid: String, cards: List<ContactCardDto>): String = store(uid, cards).id

    fun webEdit(id: String, cards: List<ContactCardDto>) {
        contacts.getValue(id).apply {
            this.cards = cards
            modifyTime = tick()
        }
    }

    fun webDelete(id: String) {
        contacts.remove(id)
    }

    private fun store(uid: String, cards: List<ContactCardDto>): Stored =
        Stored("srv-${nextId++}", uid, cards, tick()).also { contacts[it.id] = it }

    private fun Stored.toDto() = ContactDto(id = id, uid = uid, modifyTime = modifyTime, cards = cards)

    private fun uidOf(cards: List<ContactCardDto>): String =
        cards.firstOrNull { it.type == CardType.SIGNED.wireValue }
            ?.data?.lineSequence()?.firstOrNull { it.startsWith("UID:") }?.removePrefix("UID:")?.trim()
            ?: error("a created contact must carry a UID in its signed card")

    private fun gone(): HttpException =
        HttpException(Response.error<Unit>(HTTP_UNPROCESSABLE, """{"Code":$NOT_EXISTS}""".toResponseBody(null)))

    private companion object {
        const val SUCCESS = 1000
        const val BATCH = 1001
        const val DUPLICATE_UID = 2500
        const val NOT_EXISTS = 2501
        const val HTTP_UNPROCESSABLE = 422
        const val OMIT_ODDS = 10
    }
}
