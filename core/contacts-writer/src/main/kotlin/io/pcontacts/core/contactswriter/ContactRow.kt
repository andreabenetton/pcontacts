// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.contactswriter

/**
 * MVP shape — name + email only (plan §5, §17 task 16). The complete
 * version (plan §6) grows phones, addresses, organisation, notes, etc.
 * Adding fields here ripples through ContactsContractOps; intentionally
 * a single source of truth.
 *
 * `sourceId` is the Proton ContactID. We store it on `RawContacts.SOURCE_ID`
 * so the next sync run can find this row again without consulting Room.
 */
data class ContactRow(
    val sourceId: String,
    val displayName: String,
    val email: String
)
