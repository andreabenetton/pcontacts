// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.sync.contacts

import io.pcontacts.core.logging.Logger
import io.pcontacts.core.proton.api.contacts.ContactEmailDto
import io.pcontacts.core.proton.api.contacts.LabelContactEmailsRequest
import io.pcontacts.core.proton.api.contacts.LabelContactEmailsResponse
import io.pcontacts.core.proton.api.contacts.ProtonContactsApi
import io.pcontacts.core.proton.api.http.ProtonApiException
import io.pcontacts.core.proton.api.http.ProtonCodeInterceptor

/** The reason a group change is refused for a contact without an email (ADR-0027). */
const val GROUP_NEEDS_EMAIL = "group needs an email"

/**
 * Pushes the contact-group changes made on the phone (ADR-0027). `[V]` WebClients
 * `useApplyGroups`: membership lives on ContactEmails, one label per request. Android
 * puts the whole contact in a group, so joining labels all its emails and leaving
 * unlabels all of them.
 *
 * Only the deltas against [push]'s `base` (the labels Proton last reported) are sent;
 * whatever Proton changed since stays. A label is sent only to the emails that lack it
 * (or carry it, to remove), so a label deleted on Proton meanwhile sends nothing.
 */
internal class GroupMembershipPusher(
    private val contactsApi: ProtonContactsApi,
    private val logger: Logger
) {

    enum class Outcome { NOTHING, DONE, NEEDS_EMAIL }

    /** [base] or [local] null: not known, and nothing is pushed (fail closed). */
    suspend fun push(id: String, base: Set<String>?, local: Set<String>?): Outcome {
        if (base == null || local == null) return Outcome.NOTHING
        val added = local - base
        val removed = base - local
        if (added.isEmpty() && removed.isEmpty()) return Outcome.NOTHING
        val emails = contactsApi.getContact(id).contact.contactEmails
        if (added.isNotEmpty() && emails.isEmpty()) return Outcome.NEEDS_EMAIL
        for (label in added) {
            val ids = emails.without(label)
            if (ids.isNotEmpty()) requireAccepted(contactsApi.labelContactEmails(LabelContactEmailsRequest(label, ids)))
        }
        for (label in removed) {
            val ids = emails.with(label)
            if (ids.isEmpty()) continue
            requireAccepted(contactsApi.unlabelContactEmails(LabelContactEmailsRequest(label, ids)))
        }
        logger.info { "push: groups +${added.size} -${removed.size} idTag=${id.hashCode()}" }
        return Outcome.DONE
    }

    private fun List<ContactEmailDto>.without(label: String) = filter { label !in it.labelIds }.map { it.id }

    private fun List<ContactEmailDto>.with(label: String) = filter { label in it.labelIds }.map { it.id }

    /** `[U]` the answer's shape: a bare 1000, or 1001 with one Code per email; anything else is a refusal. */
    private fun requireAccepted(response: LabelContactEmailsResponse) {
        val refused = response.responses.firstOrNull { it.response.code != ProtonCodeInterceptor.SUCCESS_CODE }
        if (refused != null) throw ProtonApiException(refused.response.code, null)
    }

    companion object {
        /** The stored base: sorted, comma-joined label IDs; empty string means no groups. */
        fun joined(labels: Collection<String>): String = labels.sorted().joinToString(",")

        fun split(stored: String?): Set<String>? = stored?.split(',')?.filterTo(HashSet()) { it.isNotEmpty() }
    }
}
