// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.contactswriter

/**
 * One field found on a linked (same aggregate Contact, other account)
 * RawContact that the Proton copy lacks. The variants mirror the
 * [ContactRow] collections the additive writer can append (ADR-0023).
 */
sealed interface LinkedField {
    data class PhoneNumber(val phone: PhoneEntry) : LinkedField
    data class EmailAddress(val address: String) : LinkedField
    data class Address(val address: PostalAddress) : LinkedField
    data class Org(val organization: Organization) : LinkedField
    data class NoteText(val note: String) : LinkedField
    data class Im(val account: ImAccount) : LinkedField
}

/**
 * `sourceAccountTypes` lists the owning account type of every linked
 * RawContact that carries the value (e.g. `com.whatsapp`; null for
 * device-local rows), in cluster order. Display-only — the app resolves
 * them to labels — and never written anywhere.
 */
data class LinkedFieldCandidate(
    val field: LinkedField,
    val sourceAccountTypes: List<String?>
)

data class LinkedContactCandidates(
    val protonRawContactId: Long,
    val candidates: List<LinkedFieldCandidate>
)
