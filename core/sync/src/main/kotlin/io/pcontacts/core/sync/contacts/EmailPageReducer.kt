// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.sync.contacts

import io.pcontacts.core.contactswriter.ContactRow
import io.pcontacts.core.proton.api.contacts.ContactEmailDto

/**
 * Groups a flat list of `ContactEmailDto` by ContactID and projects each
 * group to the single `ContactRow` that the email-only MVP writes. One
 * Proton contact can have many emails; for MVP we ship the "most
 * prominent" one (Defaults DESC, then Order ASC — Proton's own
 * convention for picking a contact's default email in
 * packages/shared/lib/contacts/properties.ts [V]).
 *
 * The complete version (plan §6) writes one Email Data row per email
 * under the same RawContact and stops needing this reducer.
 */
object EmailPageReducer {

    fun reduce(emails: List<ContactEmailDto>): Map<String, ContactRow> =
        emails.groupBy { it.contactId }
            .mapValues { (_, group) ->
                val primary = group
                    .sortedWith(
                        compareByDescending<ContactEmailDto> { it.defaults }
                            .thenBy { it.order }
                    )
                    .first()
                ContactRow(
                    sourceId = primary.contactId,
                    displayName = primary.name.ifBlank { primary.email },
                    email = primary.email
                )
            }
}
