// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.sync.contacts

import io.pcontacts.core.contactswriter.ContactRow
import io.pcontacts.core.contactswriter.ImProtocol
import io.pcontacts.core.contactswriter.PhoneType
import io.pcontacts.core.contactswriter.PostalAddressType
import io.pcontacts.core.protoncontacts.DecryptedAddress
import io.pcontacts.core.protoncontacts.DecryptedContact
import io.pcontacts.core.protoncontacts.DecryptedEmail
import io.pcontacts.core.protoncontacts.DecryptedIm
import io.pcontacts.core.protoncontacts.DecryptedOrganization
import io.pcontacts.core.protoncontacts.DecryptedPhone
import io.pcontacts.core.protoncontacts.DecryptedPhoto
import io.pcontacts.core.protoncontacts.DecryptedStructuredName

/**
 * Inverse of [DecryptedContactToRow]. Reconstructs a [DecryptedContact]
 * from a [ContactRow] so the write engine can feed
 * [io.pcontacts.core.protoncontacts.ContactSerializer].
 *
 * Lossy by design: [DecryptedContactToRow] collapses multi-element
 * structured-name lists (additionalNames, prefixes, suffixes) to their
 * first entry, and type tokens are normalized to enum values. The
 * round-trip is therefore approximate — `convert(forward(dc))` may
 * differ from `dc` in list order and collapsed multi-values.
 */
internal object RowToDecryptedContact {

    fun convert(
        row: ContactRow,
        protonContactId: String,
        protonUid: String? = null
    ): DecryptedContact = DecryptedContact(
        protonContactId = protonContactId,
        protonUid = protonUid,
        fullName = row.displayName,
        structuredName = row.structuredName?.let(::toDecryptedStructured),
        emails = row.emails.mapIndexed { index, addr ->
            DecryptedEmail(address = addr, isPrimary = index == 0)
        },
        phones = row.phones.map(::toDecryptedPhone),
        addresses = row.addresses.map(::toDecryptedAddress),
        organization = row.organization?.let(::toDecryptedOrganization),
        notes = row.notes,
        imAccounts = row.imAccounts.map(::toDecryptedIm),
        photo = row.photo?.let { DecryptedPhoto(data = it.data) },
        verified = true,
        cardCount = 2,
        unverifiedCardCount = 0
    )

    private fun toDecryptedStructured(
        sn: io.pcontacts.core.contactswriter.StructuredName
    ): DecryptedStructuredName? {
        if (sn.given == null && sn.family == null && sn.middle == null &&
            sn.prefix == null && sn.suffix == null
        ) return null
        return DecryptedStructuredName(
            given = sn.given,
            family = sn.family,
            additionalNames = listOfNotNull(sn.middle),
            prefixes = listOfNotNull(sn.prefix),
            suffixes = listOfNotNull(sn.suffix)
        )
    }

    private fun toDecryptedPhone(
        entry: io.pcontacts.core.contactswriter.PhoneEntry
    ) = DecryptedPhone(
        number = entry.number,
        types = phoneTypeToTokens(entry.type),
        isPrimary = entry.isPrimary
    )

    private fun toDecryptedAddress(
        addr: io.pcontacts.core.contactswriter.PostalAddress
    ) = DecryptedAddress(
        poBox = addr.poBox,
        extendedAddress = addr.neighborhood,
        street = addr.street,
        locality = addr.city,
        region = addr.region,
        postalCode = addr.postcode,
        country = addr.country,
        types = postalTypeToTokens(addr.type),
        isPrimary = addr.isPrimary
    )

    private fun toDecryptedOrganization(
        org: io.pcontacts.core.contactswriter.Organization
    ) = DecryptedOrganization(
        company = org.company,
        department = org.department,
        title = org.title
    )

    private fun toDecryptedIm(
        im: io.pcontacts.core.contactswriter.ImAccount
    ) = DecryptedIm(
        handle = im.handle,
        protocol = imProtocolToScheme(im.protocol, im.customProtocol)
    )

    private fun phoneTypeToTokens(type: PhoneType): List<String> = when (type) {
        PhoneType.HOME -> listOf("home")
        PhoneType.WORK -> listOf("work")
        PhoneType.MOBILE -> listOf("cell")
        PhoneType.FAX_HOME -> listOf("fax", "home")
        PhoneType.FAX_WORK -> listOf("fax", "work")
        PhoneType.PAGER -> listOf("pager")
        PhoneType.MAIN -> listOf("main")
        PhoneType.OTHER -> emptyList()
    }

    private fun postalTypeToTokens(type: PostalAddressType): List<String> = when (type) {
        PostalAddressType.HOME -> listOf("home")
        PostalAddressType.WORK -> listOf("work")
        PostalAddressType.OTHER -> emptyList()
    }

    private fun imProtocolToScheme(protocol: ImProtocol, custom: String?): String? = when (protocol) {
        ImProtocol.JABBER -> "xmpp"
        ImProtocol.AIM -> "aim"
        ImProtocol.MSN -> "msn"
        ImProtocol.YAHOO -> "yahoo"
        ImProtocol.SKYPE -> "skype"
        ImProtocol.QQ -> "qq"
        ImProtocol.GOOGLE_TALK -> "googletalk"
        ImProtocol.ICQ -> "icq"
        ImProtocol.NETMEETING -> "netmeeting"
        ImProtocol.CUSTOM -> custom
    }
}
