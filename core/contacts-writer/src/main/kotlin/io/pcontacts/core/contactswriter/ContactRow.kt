// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.contactswriter

/**
 * MVP shape — name + email(s) (plan §5, §17 task 18). The complete
 * version (plan §6) grows phones, addresses, organisation, notes, etc.
 * Adding fields here ripples through ContactsContractOps; intentionally
 * a single source of truth.
 *
 * `sourceId` is the Proton ContactID. We store it on `RawContacts.SOURCE_ID`
 * so the next sync run can find this row again without consulting Room.
 *
 * `emails` order is significant: position 0 is treated as the "primary"
 * Email row (`IS_PRIMARY = 1`, `IS_SUPER_PRIMARY = 1`). Callers should
 * sort by their domain notion of "primary first" before passing in.
 */
data class ContactRow(
    val sourceId: String,
    val displayName: String,
    val emails: List<String>
) {
    init {
        require(emails.isNotEmpty()) { "ContactRow must carry at least one email" }
    }
}
