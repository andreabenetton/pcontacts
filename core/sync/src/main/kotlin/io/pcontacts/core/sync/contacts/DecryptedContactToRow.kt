// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.sync.contacts

import io.pcontacts.core.contactswriter.ContactRow
import io.pcontacts.core.protoncontacts.DecryptedContact

/**
 * Bridge from the rich `DecryptedContact` model (FN/N/EMAIL[]/TEL/...)
 * to the MVP-shaped `ContactRow` (sourceId, displayName, email) that
 * :core:contacts-writer currently understands.
 *
 * Task 18 expands the writer surface to multi-email + TEL + N pieces;
 * this translator collapses to a single email row for now. Picking
 * order mirrors EmailPageReducer (primary first, then first).
 *
 * Returns null when the decrypted contact has no email at all — the
 * MVP writer can't represent name-only contacts. Such contacts are
 * also absent from `/contacts/v4/contacts/emails` (the listing
 * endpoint we enumerate against), so this branch is mostly defensive.
 */
internal object DecryptedContactToRow {

    fun convert(decrypted: DecryptedContact): ContactRow? {
        val primary = decrypted.emails.firstOrNull { it.isPrimary }
            ?: decrypted.emails.firstOrNull()
            ?: return null

        val displayName = decrypted.fullName?.takeIf { it.isNotBlank() }
            ?: primary.address    // fall back to the email address itself

        return ContactRow(
            sourceId = decrypted.protonContactId,
            displayName = displayName,
            email = primary.address
        )
    }
}
