// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.sync.contacts

import io.pcontacts.core.contactswriter.ContactRow
import io.pcontacts.core.proton.api.contacts.ContactEmailDto

/**
 * Groups a flat list of `ContactEmailDto` by ContactID and projects
 * each group to the `ContactRow` the email-only engine writes. One
 * Proton contact can have many emails; we emit ALL of them, with
 * the primary at position 0 (the writer interprets position 0 as
 * IS_SUPER_PRIMARY per ContactsContractOps).
 *
 * Primary-pick order ([V] from packages/shared/lib/contacts/properties.ts):
 * Defaults DESC, then Order ASC.
 */
object EmailPageReducer {

    fun reduce(emails: List<ContactEmailDto>): Map<String, ContactRow> =
        emails.groupBy { it.contactId }
            .mapValues { (_, group) ->
                val sorted = group.sortedWith(
                    compareByDescending<ContactEmailDto> { it.defaults }
                        .thenBy { it.order }
                )
                val primary = sorted.first()
                ContactRow(
                    sourceId = primary.contactId,
                    displayName = primary.name.ifBlank { primary.email },
                    emails = sorted.map { it.email }
                )
            }
}
