// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.sync.contacts

import io.pcontacts.core.contactswriter.ContactRow
import io.pcontacts.core.contactswriter.PhoneEntry
import io.pcontacts.core.contactswriter.PhoneTypeMapper
import io.pcontacts.core.contactswriter.StructuredName
import io.pcontacts.core.protoncontacts.DecryptedContact
import io.pcontacts.core.protoncontacts.DecryptedStructuredName

/**
 * Bridge from the rich `DecryptedContact` model (FN / N / EMAIL[] /
 * TEL[]) to the writer-shaped `ContactRow` (sourceId, displayName,
 * structuredName?, emails[], phones[]).
 *
 *   - structuredName: pieces from the decrypted N property; lists
 *     (additionalNames / prefixes / suffixes) collapsed to the first
 *     non-blank entry (ContactsContract surfaces one column per piece).
 *   - emails: ordered primary-first (the writer interprets position 0
 *     as IS_SUPER_PRIMARY).
 *   - phones: ordered primary-first too; type tokens mapped via
 *     `PhoneTypeMapper.fromTokens` to the MVP `PhoneType` enum.
 *
 * Returns null when the decrypted contact has no email AND no phone —
 * the writer can't represent name-only contacts. Such contacts are
 * also absent from `/contacts/v4/contacts/emails` (the email path's
 * enumeration source), so this branch is mostly defensive.
 */
internal object DecryptedContactToRow {

    fun convert(decrypted: DecryptedContact): ContactRow? {
        // Primary-first ordering for both emails and phones — same
        // partition pattern; relative order within each side is stable.
        val (emailPrimary, emailOthers) = decrypted.emails.partition { it.isPrimary }
        val emails = (emailPrimary + emailOthers).map { it.address }.filter { it.isNotBlank() }

        val (phonePrimary, phoneOthers) = decrypted.phones.partition { it.isPrimary }
        val phones = (phonePrimary + phoneOthers)
            .map { p ->
                PhoneEntry(
                    number = p.number,
                    type = PhoneTypeMapper.fromTokens(p.types),
                    isPrimary = p.isPrimary
                )
            }
            .filter { it.number.isNotBlank() }

        if (emails.isEmpty() && phones.isEmpty()) return null

        val displayName = decrypted.fullName?.takeIf { it.isNotBlank() }
            ?: emails.firstOrNull()
            ?: phones.first().number

        return ContactRow(
            sourceId = decrypted.protonContactId,
            displayName = displayName,
            structuredName = toWriterStructured(decrypted.structuredName),
            emails = emails,
            phones = phones
        )
    }

    /**
     * Collapses the multi-element vCard pieces to the single-column
     * ContactsContract shape. Returns null when nothing usable remains
     * after collapsing — keeps the writer's "null = no pieces" contract
     * tight.
     */
    private fun toWriterStructured(decrypted: DecryptedStructuredName?): StructuredName? {
        if (decrypted == null) return null
        val given = decrypted.given?.takeIf { it.isNotBlank() }
        val family = decrypted.family?.takeIf { it.isNotBlank() }
        val middle = decrypted.additionalNames.firstOrNull { it.isNotBlank() }
        val prefix = decrypted.prefixes.firstOrNull { it.isNotBlank() }
        val suffix = decrypted.suffixes.firstOrNull { it.isNotBlank() }
        if (given == null && family == null && middle == null && prefix == null && suffix == null) {
            return null
        }
        return StructuredName(
            given = given,
            family = family,
            middle = middle,
            prefix = prefix,
            suffix = suffix
        )
    }
}
