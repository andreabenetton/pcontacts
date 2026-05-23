// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.contactswriter

/**
 * MVP shape — name (FN + N pieces), email(s), and phone(s)
 * (plan §5, §17 task 18). The complete version (plan §6) grows
 * addresses, organisation, notes, etc. Adding fields here ripples
 * through ContactsContractOps; intentionally a single source of truth.
 *
 * `sourceId` is the Proton ContactID. We store it on `RawContacts.SOURCE_ID`
 * so the next sync run can find this row again without consulting Room.
 *
 * `displayName` always lands in `StructuredName.DISPLAY_NAME` so the
 * system Contacts UI always shows something. `structuredName` populates
 * the per-piece columns (GIVEN_NAME / FAMILY_NAME / MIDDLE_NAME /
 * PREFIX / SUFFIX) on the same Data row; null means "no structured
 * pieces to write" (DISPLAY_NAME alone covers the user).
 *
 * `emails` order is significant: position 0 is treated as the
 * "primary" Email row (`IS_PRIMARY` + `IS_SUPER_PRIMARY`). If any
 * `PhoneEntry.isPrimary == true`, that wins; otherwise position 0 of
 * `phones` is treated as primary by the writer.
 *
 * Init guard: at least one email OR one phone is required —
 * name-only contacts aren't representable yet (they're absent from
 * `/contacts/v4/contacts/emails` for the email-only sync path anyway,
 * and the decrypt path produces them only when both lists are
 * server-side empty).
 */
data class ContactRow(
    val sourceId: String,
    val displayName: String,
    val structuredName: StructuredName? = null,
    val emails: List<String>,
    val phones: List<PhoneEntry> = emptyList()
) {
    init {
        require(emails.isNotEmpty() || phones.isNotEmpty()) {
            "ContactRow must carry at least one email or phone"
        }
    }
}

/**
 * ContactsContract's StructuredName Data row carries one column per
 * piece — there's no "additionalNames[]" list column, so vCard
 * fragments with multiple middle names / prefixes / suffixes get
 * collapsed to their first non-blank entry by DecryptedContactToRow.
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
 * MVP subset of `ContactsContract.CommonDataKinds.Phone.TYPE_*`. The
 * mapping from vCard TEL TYPE tokens lives in `PhoneTypeMapper`.
 * Tokens with no Android equivalent fall back to `OTHER`;
 * Phone.TYPE_CUSTOM + LABEL support lands with the complete version.
 */
enum class PhoneType {
    HOME, WORK, MOBILE, FAX_HOME, FAX_WORK, PAGER, MAIN, OTHER
}
