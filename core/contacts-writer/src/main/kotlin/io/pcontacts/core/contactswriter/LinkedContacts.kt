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

/** Whether the field can reach the person — what [ContactRow] requires at least one of. */
val LinkedField.reachesContact: Boolean
    get() = when (this) {
        is LinkedField.PhoneNumber, is LinkedField.EmailAddress, is LinkedField.Address, is LinkedField.Im -> true
        is LinkedField.Org, is LinkedField.NoteText -> false
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

/** Name a new Proton contact is created with, taken from the first linked row that has one. */
data class LinkedContactName(
    val displayName: String?,
    val structuredName: StructuredName?
)

/**
 * `protonRawContactId` is null when the aggregate has no Proton copy;
 * the candidates are then everything the linked rows carry and
 * [name] is what a new Proton contact would be called. With a Proton
 * copy present, [name] is null and the candidates are only the fields
 * it lacks.
 */
data class LinkedContactCandidates(
    val protonRawContactId: Long?,
    val name: LinkedContactName?,
    val candidates: List<LinkedFieldCandidate>
)
