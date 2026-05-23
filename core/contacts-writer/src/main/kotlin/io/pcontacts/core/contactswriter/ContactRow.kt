// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.contactswriter

/**
 * Writer-shaped contact row. Holds everything a single ContactsContract
 * RawContact + its child Data rows need (plan §8 mapping).
 *
 * `sourceId` is the Proton ContactID. We store it on `RawContacts.SOURCE_ID`
 * so the next sync run can find this row again without consulting Room.
 *
 * Field semantics:
 *   - `displayName` always lands in StructuredName.DISPLAY_NAME.
 *   - `structuredName` populates the per-piece columns (GIVEN/FAMILY/
 *     MIDDLE/PREFIX/SUFFIX) on the same Data row when non-null.
 *   - `emails` order is significant: position 0 is the "primary"
 *     (IS_PRIMARY + IS_SUPER_PRIMARY).
 *   - `phones` may flag a primary explicitly via PhoneEntry.isPrimary;
 *     otherwise position 0 wins.
 *   - `addresses` likewise (position 0 OR explicit isPrimary).
 *   - `organization`: one ContactsContract.Organization Data row per
 *     contact (multi-org is rare and not yet modelled).
 *   - `notes`: one Note Data row per entry.
 *   - `imAccounts`: one Im Data row per entry.
 *   - `photo`: one Photo Data row (inline). Display-stream / large
 *     photos via RawContacts.DisplayPhoto land with the complete version.
 *
 * Init guard: contact must carry at least one user-actionable field
 * (email / phone / address / im). Name-only / note-only / org-only
 * contacts aren't representable yet; they're absent from the listing
 * endpoints we enumerate against anyway.
 */
data class ContactRow(
    val sourceId: String,
    val displayName: String,
    val structuredName: StructuredName? = null,
    val emails: List<String>,
    val phones: List<PhoneEntry> = emptyList(),
    val addresses: List<PostalAddress> = emptyList(),
    val organization: Organization? = null,
    val notes: List<String> = emptyList(),
    val imAccounts: List<ImAccount> = emptyList(),
    val photo: ContactPhoto? = null
) {
    init {
        require(
            emails.isNotEmpty() ||
                phones.isNotEmpty() ||
                addresses.isNotEmpty() ||
                imAccounts.isNotEmpty()
        ) {
            "ContactRow must carry at least one email, phone, address, or IM account"
        }
    }
}

/**
 * ContactsContract's StructuredName Data row carries one column per
 * piece — there's no "additionalNames[]" list column, so vCard
 * fragments with multiple middle names / prefixes / suffixes get
 * collapsed to their first non-blank element by DecryptedContactToRow.
 */
data class StructuredName(
    val given: String? = null,
    val family: String? = null,
    val middle: String? = null,
    val prefix: String? = null,
    val suffix: String? = null
)

data class PhoneEntry(
    val number: String,
    val type: PhoneType = PhoneType.OTHER,
    val isPrimary: Boolean = false
)

/**
 * MVP subset of `ContactsContract.CommonDataKinds.Phone.TYPE_*`.
 * Token mapping lives in `PhoneTypeMapper`. CUSTOM + LABEL ships
 * with the complete version.
 */
enum class PhoneType {
    HOME, WORK, MOBILE, FAX_HOME, FAX_WORK, PAGER, MAIN, OTHER
}

/**
 * StructuredPostal Data row content. Every component nullable; the
 * writer skips columns whose value is null or blank. ContactsContract
 * also has a FORMATTED_ADDRESS column the system populates from the
 * structured pieces — we don't write it explicitly.
 */
data class PostalAddress(
    val poBox: String? = null,
    val neighborhood: String? = null,   // RFC 6350 §6.3.1 "extended-address"
    val street: String? = null,
    val city: String? = null,
    val region: String? = null,
    val postcode: String? = null,
    val country: String? = null,
    val type: PostalAddressType = PostalAddressType.OTHER,
    val isPrimary: Boolean = false
)

enum class PostalAddressType { HOME, WORK, OTHER }

/**
 * Organization Data row content. ContactsContract surfaces these as
 * separate columns on the same row (COMPANY, DEPARTMENT, TITLE).
 */
data class Organization(
    val company: String? = null,
    val department: String? = null,
    val title: String? = null
)

/**
 * Im Data row content. `protocol` is the constant tier ContactsContract
 * recognises; CUSTOM tiers carry `customProtocol` as the label. The
 * URI-scheme → Im.PROTOCOL_* mapping lives in `ImProtocolMapper`.
 */
data class ImAccount(
    val handle: String,
    val protocol: ImProtocol = ImProtocol.CUSTOM,
    val customProtocol: String? = null,
    val type: ImAccountType = ImAccountType.OTHER
)

enum class ImProtocol {
    JABBER, AIM, MSN, YAHOO, SKYPE, QQ, GOOGLE_TALK, ICQ, NETMEETING, CUSTOM
}

/** Im row TYPE column — home / work / other. */
enum class ImAccountType { HOME, WORK, OTHER }

/**
 * Inline Photo Data row content. ContactsContract's Photo.PHOTO column
 * accepts a BLOB; large images should ideally go to RawContacts.DisplayPhoto
 * via openOutputStream. MVP writes whatever bytes arrive and logs a
 * warning if they're past the safe inline cap.
 *
 * Custom equals/hashCode because the default data-class behaviour on
 * `ByteArray` uses reference identity; value equality is what callers
 * (including the engine's hash) expect.
 */
data class ContactPhoto(
    val data: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ContactPhoto) return false
        return data.contentEquals(other.data)
    }

    override fun hashCode(): Int = data.contentHashCode()
}
