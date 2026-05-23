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
 * Fields beyond `fullName` / `structuredName` / `emails` / `phones`
 * (Address, Organization, Note, etc.) land with the complete version
 * (Plan §6); the data class holds the MVP shape today.
 */
data class DecryptedContact(
    val protonContactId: String,
    val protonUid: String?,
    val fullName: String?,
    val structuredName: DecryptedStructuredName? = null,
    val emails: List<DecryptedEmail>,
    val phones: List<DecryptedPhone> = emptyList(),
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
