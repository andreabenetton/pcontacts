// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.protoncontacts

/**
 * Merged, decrypted contact — the input the ContactsContract writer
 * eventually consumes (task 18). Intentionally a thin Kotlin model
 * rather than ez-vcard's `VCard` type so :core:proton-contacts owns
 * the public surface and downstream modules don't pick up ez-vcard at
 * compile time.
 *
 * `verified` is true iff every SIGNED / ENCRYPTED_AND_SIGNED card on
 * the source contact verified successfully. The flag lands in
 * `contact_map.is_verified` (ADR-0008) so a future settings screen can
 * surface "X contacts could not be verified" without exposing which.
 *
 * Plan §6 / §8 coverage:
 *   FN / N           → fullName / structuredName
 *   EMAIL            → emails
 *   TEL              → phones
 *   ADR              → addresses
 *   ORG + TITLE      → organization
 *   NOTE             → notes
 *   IMPP             → imAccounts
 *   PHOTO (inline)   → photo
 *
 * Groups (CATEGORIES + Proton LabelIDs → GroupMembership) ship in a
 * later commit — they need the labels-listing API and GroupMap
 * lifecycle plumbing.
 */
data class DecryptedContact(
    val protonContactId: String,
    val protonUid: String?,
    val fullName: String?,
    val structuredName: DecryptedStructuredName? = null,
    val emails: List<DecryptedEmail>,
    val phones: List<DecryptedPhone> = emptyList(),
    val addresses: List<DecryptedAddress> = emptyList(),
    val organization: DecryptedOrganization? = null,
    val notes: List<String> = emptyList(),
    val imAccounts: List<DecryptedIm> = emptyList(),
    val photo: DecryptedPhoto? = null,
    val verified: Boolean,
    val cardCount: Int,
    val unverifiedCardCount: Int
) {
    companion object {
        fun empty(protonContactId: String) = DecryptedContact(
            protonContactId = protonContactId,
            protonUid = null,
            fullName = null,
            structuredName = null,
            emails = emptyList(),
            phones = emptyList(),
            addresses = emptyList(),
            organization = null,
            notes = emptyList(),
            imAccounts = emptyList(),
            photo = null,
            verified = true,
            cardCount = 0,
            unverifiedCardCount = 0
        )
    }
}

/**
 * vCard `N` projection. All fields optional; merger sets null /
 * empty when the source vCard omits the corresponding component.
 * The writer collapses each list to its first non-blank entry
 * (ContactsContract surfaces only one MIDDLE_NAME / PREFIX /
 * SUFFIX column per StructuredName Data row).
 */
data class DecryptedStructuredName(
    val given: String? = null,
    val family: String? = null,
    val additionalNames: List<String> = emptyList(),
    val prefixes: List<String> = emptyList(),
    val suffixes: List<String> = emptyList()
)

data class DecryptedEmail(
    val address: String,
    val types: List<String> = emptyList(),
    val isPrimary: Boolean = false
)

/**
 * vCard `TEL` projection. `types` carries the raw vCard TYPE tokens
 * ("home", "work", "cell", "fax", ...) — the writer maps these to
 * Android's Phone.TYPE_* constants via PhoneTypeMapper.
 */
data class DecryptedPhone(
    val number: String,
    val types: List<String> = emptyList(),
    val isPrimary: Boolean = false
)

/**
 * vCard `ADR` projection. RFC 6350 §6.3.1 component order:
 *   po-box ; ext ; street ; locality ; region ; postal-code ; country
 *
 * ContactsContract has columns for every piece (POBOX, NEIGHBORHOOD
 * for ext, STREET, CITY, REGION, POSTCODE, COUNTRY); we keep all
 * seven on the model so the writer's projection is lossless.
 */
data class DecryptedAddress(
    val poBox: String? = null,
    val extendedAddress: String? = null,
    val street: String? = null,
    val locality: String? = null,
    val region: String? = null,
    val postalCode: String? = null,
    val country: String? = null,
    val types: List<String> = emptyList(),
    val isPrimary: Boolean = false
)

/**
 * vCard `ORG` + first `TITLE`. Proton ships multi-component ORG
 * (`Company;Department`) — we keep both and the title separately.
 * Only the first TITLE is surfaced; multi-role contacts are rare
 * enough that the additional rows aren't worth a list column.
 */
data class DecryptedOrganization(
    val company: String? = null,
    val department: String? = null,
    val title: String? = null
)

/**
 * vCard `IMPP` projection. RFC 6350 §6.4.3 — IMPP value is a URI:
 *   xmpp:alice@example.com
 *   skype:alice.live
 *   sip:alice@voip.example
 *
 * `protocol` is the URI scheme; `handle` is the rest. ContactsContract
 * recognises a fixed set of protocols (JABBER / AIM / MSN / etc.) plus
 * a CUSTOM tier for everything else — the writer's ImProtocolMapper
 * handles the matrix.
 */
data class DecryptedIm(
    val handle: String,
    val protocol: String? = null,
    val types: List<String> = emptyList()
)

/**
 * vCard inline `PHOTO`. URL-reference photos (PHOTO: URL form) aren't
 * fetched by MVP — only inline binary blobs land here.
 *
 * Custom equals/hashCode because the default data-class behaviour on
 * `ByteArray` uses reference identity; we want value equality so the
 * sync engine's hash comparison stays meaningful.
 */
data class DecryptedPhoto(
    val data: ByteArray,
    val mimeType: String? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DecryptedPhoto) return false
        return data.contentEquals(other.data) && mimeType == other.mimeType
    }

    override fun hashCode(): Int =
        31 * data.contentHashCode() + (mimeType?.hashCode() ?: 0)
}
