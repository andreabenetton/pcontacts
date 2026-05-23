// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.sync.contacts

import io.pcontacts.core.contactswriter.ContactRow
import io.pcontacts.core.protoncontacts.DecryptedContact

/**
 * Bridge from the rich `DecryptedContact` model (FN/N/EMAIL[]/TEL/...)
 * to the writer-shaped `ContactRow` (sourceId, displayName, emails[]).
 *
 * Emits ALL of the contact's emails. Order: primary (`isPrimary == true`)
 * first, then the remainder in their original sequence. The writer
 * interprets position 0 as IS_SUPER_PRIMARY.
 *
 * Task 18 will grow this to also project StructuredName (FN + N
 * pieces), phones, etc.; for now the additional fields land on
 * future ContactRow extensions and the projection grows in lockstep.
 *
 * Returns null when the decrypted contact has no email at all — the
 * MVP writer can't represent name-only contacts. Such contacts are
 * also absent from `/contacts/v4/contacts/emails` (the listing
 * endpoint we enumerate against), so this branch is mostly defensive.
 */
internal object DecryptedContactToRow {

    fun convert(decrypted: DecryptedContact): ContactRow? {
        if (decrypted.emails.isEmpty()) return null

        // Stable order: primary first, then the rest in their original
        // position. partition() keeps relative order within each side.
        val (primary, others) = decrypted.emails.partition { it.isPrimary }
        val ordered = (primary + others).map { it.address }.filter { it.isNotBlank() }
        if (ordered.isEmpty()) return null

        val displayName = decrypted.fullName?.takeIf { it.isNotBlank() }
            ?: ordered.first()

        return ContactRow(
            sourceId = decrypted.protonContactId,
            displayName = displayName,
            emails = ordered
        )
    }
}
