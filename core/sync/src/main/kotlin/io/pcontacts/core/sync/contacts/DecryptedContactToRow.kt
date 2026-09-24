// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.sync.contacts

import io.pcontacts.core.contactswriter.ContactPhoto
import io.pcontacts.core.contactswriter.ContactRow
import io.pcontacts.core.contactswriter.ImAccount
import io.pcontacts.core.contactswriter.ImProtocol
import io.pcontacts.core.contactswriter.ImProtocolMapper
import io.pcontacts.core.contactswriter.Organization
import io.pcontacts.core.contactswriter.PhoneEntry
import io.pcontacts.core.contactswriter.PhoneTypeMapper
import io.pcontacts.core.contactswriter.PostalAddress
import io.pcontacts.core.contactswriter.PostalAddressTypeMapper
import io.pcontacts.core.contactswriter.StructuredName
import io.pcontacts.core.protoncontacts.DecryptedAddress
import io.pcontacts.core.protoncontacts.DecryptedContact
import io.pcontacts.core.protoncontacts.DecryptedIm
import io.pcontacts.core.protoncontacts.DecryptedOrganization
import io.pcontacts.core.protoncontacts.DecryptedPhoto
import io.pcontacts.core.protoncontacts.DecryptedStructuredName

/**
 * Bridge from the rich `DecryptedContact` model (FN / N / EMAIL[] /
 * TEL[] / ADR[] / ORG / NOTE[] / IMPP[] / PHOTO) to the writer-shaped
 * `ContactRow`.
 *
 *   - structuredName: pieces from the decrypted N property; lists
 *     (additionalNames / prefixes / suffixes) collapsed to the first
 *     non-blank entry (ContactsContract surfaces one column per piece).
 *   - emails: ordered primary-first.
 *   - phones: ordered primary-first; types mapped via PhoneTypeMapper.
 *   - addresses: ordered primary-first; types mapped via
 *     PostalAddressTypeMapper.
 *   - organization: passed through 1:1.
 *   - notes: passed through 1:1.
 *   - imAccounts: URI scheme → ImProtocol via ImProtocolMapper;
 *     unknown schemes ride as CUSTOM with the scheme as label.
 *   - photo: passed through with the bytes copied verbatim.
 *
 * Returns null only when the contact has neither a name nor any
 * field — a Proton contact needs no phone or email to be valid, and a
 * name-only contact must reach the provider so a stale local email
 * disappears when the server drops it. The writer's init guard
 * rejects the same condition; this branch is an early return that
 * avoids a no-op throw.
 */
internal object DecryptedContactToRow {

    fun convert(decrypted: DecryptedContact): ContactRow? {
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

        val (addressPrimary, addressOthers) = decrypted.addresses.partition { it.isPrimary }
        val addresses = (addressPrimary + addressOthers).map(::toWriterPostal)

        val imAccounts = decrypted.imAccounts.map(::toWriterImAccount)

        // Use only real Proton names (FN or projected from N). DO NOT
        // synthesize a displayName from a phone / email / IM handle —
        // that string lands in StructuredName.DISPLAY_NAME and lets
        // Android's aggregator overwrite the real name of a local
        // RawContact we merged into (e.g. a WhatsApp / SIM entry with
        // the same phone). When this is null, ContactsContractOps omits
        // the StructuredName row entirely.
        val displayName = decrypted.fullName?.takeIf { it.isNotBlank() }

        // A name alone is a contact (Proton needs no phone or email); only a
        // contact that says nothing representable at all yields no row.
        val structuredName = toWriterStructured(decrypted.structuredName)
        val organization = toWriterOrganization(decrypted.organization)
        val notes = decrypted.notes.filter { it.isNotBlank() }
        val photo = toWriterPhoto(decrypted.photo)
        val birthday = decrypted.birthday?.takeIf { it.isNotBlank() }
        val anniversary = decrypted.anniversary?.takeIf { it.isNotBlank() }
        val nicknames = decrypted.nicknames.filter { it.isNotBlank() }
        val websites = decrypted.websites.filter { it.isNotBlank() }
        val hasName = displayName != null || structuredName != null
        val hasField = emails.isNotEmpty() || phones.isNotEmpty() || addresses.isNotEmpty() ||
            imAccounts.isNotEmpty() || organization != null || notes.isNotEmpty() || photo != null
        val hasExtra = birthday != null || anniversary != null || nicknames.isNotEmpty() || websites.isNotEmpty()
        if (!hasName && !hasField && !hasExtra) return null

        return ContactRow(
            sourceId = decrypted.protonContactId,
            displayName = displayName,
            structuredName = structuredName,
            emails = emails,
            phones = phones,
            addresses = addresses,
            organization = organization,
            notes = notes,
            imAccounts = imAccounts,
            birthday = birthday,
            anniversary = anniversary,
            nicknames = nicknames,
            websites = websites,
            photo = photo
        )
    }

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

    private fun toWriterPostal(decrypted: DecryptedAddress): PostalAddress = PostalAddress(
        poBox = decrypted.poBox,
        neighborhood = decrypted.extendedAddress,
        street = decrypted.street,
        city = decrypted.locality,
        region = decrypted.region,
        postcode = decrypted.postalCode,
        country = decrypted.country,
        type = PostalAddressTypeMapper.fromTokens(decrypted.types),
        isPrimary = decrypted.isPrimary
    )

    private fun toWriterOrganization(decrypted: DecryptedOrganization?): Organization? {
        if (decrypted == null) return null
        return Organization(
            company = decrypted.company,
            department = decrypted.department,
            title = decrypted.title
        )
    }

    private fun toWriterImAccount(decrypted: DecryptedIm): ImAccount {
        val protocol = ImProtocolMapper.fromScheme(decrypted.protocol)
        return ImAccount(
            handle = decrypted.handle,
            protocol = protocol,
            // For CUSTOM, surface the original scheme as the label so the
            // user-visible chip shows "Matrix" / "Signal" / etc. rather
            // than the generic "im" fallback the writer hands out.
            customProtocol = if (protocol == ImProtocol.CUSTOM) decrypted.protocol else null
        )
    }

    private fun toWriterPhoto(decrypted: DecryptedPhoto?): ContactPhoto? {
        if (decrypted == null || decrypted.data.isEmpty()) return null
        return ContactPhoto(data = decrypted.data)
    }
}
