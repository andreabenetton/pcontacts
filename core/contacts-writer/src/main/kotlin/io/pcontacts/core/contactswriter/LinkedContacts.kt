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
 * A stable identity for the field: kind plus normalised value. The
 * import dialog remembers selections by this key and re-reads the
 * candidates at confirmation, so a selection survives a re-aggregation
 * (ADR-0023) and never indexes into a stale list.
 */
val LinkedField.key: String
    get() = when (this) {
        is LinkedField.PhoneNumber -> "phone:" + phone.number.filter { !it.isWhitespace() && it != '-' }
        is LinkedField.EmailAddress -> "email:" + address.trim().lowercase()
        is LinkedField.Address -> "addr:" + with(address) {
            listOf(poBox, neighborhood, street, city, region, postcode, country).joinToString("|") { it?.trim().orEmpty() }
        }
        is LinkedField.Org -> "org:" + with(organization) {
            listOf(company, department, title).joinToString("|") { it?.trim().orEmpty() }
        }
        is LinkedField.NoteText -> "note:" + note.trim()
        is LinkedField.Im -> "im:" + with(account) { "${customProtocol ?: protocol.name}:${handle.trim()}" }
    }

/** Whether the field can reach the person — what a new Proton contact is created from (ADR-0023). */
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
