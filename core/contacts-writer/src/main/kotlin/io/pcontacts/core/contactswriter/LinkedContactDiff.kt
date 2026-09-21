// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.contactswriter

/**
 * Pure diff for ADR-0023: which fields on linked RawContacts are
 * missing from the Proton copy. Values already on the Proton row are
 * never re-offered, and the same value seen on two siblings (a phone
 * stored by both WhatsApp and the SIM) is offered once, attributed to
 * the first sibling that carried it.
 *
 * Phone matching is a heuristic, not E.164 parsing: numbers are
 * reduced to their digits and treated as equal when one is a suffix of
 * the other and the shorter has at least [MIN_PHONE_SUFFIX_DIGITS]
 * digits, so `+39 333 1234567`, `0039 333 1234567` and `333 1234567`
 * collapse. Under-offering is the safe failure mode.
 */
object LinkedContactDiff {

    private const val MIN_PHONE_SUFFIX_DIGITS = 7

    /**
     * @param proton the Proton RawContact's fields; null when its Data
     *   rows carry nothing the reader represents.
     * @param siblings (owning account type, fields) per linked RawContact.
     */
    fun candidates(
        proton: ContactRow?,
        siblings: List<Pair<String?, ContactRow>>
    ): List<LinkedFieldCandidate> {
        val seen = Seen(proton)
        val out = mutableListOf<LinkedFieldCandidate>()
        for ((accountType, row) in siblings) {
            for (field in seen.newFields(row)) out += LinkedFieldCandidate(field, accountType)
        }
        return out
    }

    /** Values already accounted for — seeded from Proton, grown by each sibling. */
    private class Seen(proton: ContactRow?) {
        private val phones = proton?.phones.orEmpty().map { phoneDigits(it.number) }.toMutableList()
        private val emails = proton?.emails.orEmpty().map(::emailKey).toMutableSet()
        private val addresses = proton?.addresses.orEmpty().map(::addressKey).toMutableSet()
        private val notes = proton?.notes.orEmpty().map { it.trim() }.toMutableSet()
        private val ims = proton?.imAccounts.orEmpty().map(::imKey).toMutableSet()
        private var hasOrganization = proton?.organization?.let(::hasContent) ?: false

        fun newFields(row: ContactRow): List<LinkedField> = buildList {
            row.phones.forEach { if (addPhone(it.number)) add(LinkedField.PhoneNumber(it)) }
            row.emails.forEach { if (emails.add(emailKey(it))) add(LinkedField.EmailAddress(it)) }
            row.addresses.forEach { if (addresses.add(addressKey(it))) add(LinkedField.Address(it)) }
            row.organization?.let { if (addOrganization(it)) add(LinkedField.Org(it)) }
            row.notes.forEach { if (it.isNotBlank() && notes.add(it.trim())) add(LinkedField.NoteText(it)) }
            row.imAccounts.forEach { if (ims.add(imKey(it))) add(LinkedField.Im(it)) }
        }

        private fun addPhone(number: String): Boolean {
            val digits = phoneDigits(number)
            if (digits.isEmpty() || phones.any { samePhone(it, digits) }) return false
            phones += digits
            return true
        }

        private fun addOrganization(org: Organization): Boolean {
            if (hasOrganization || !hasContent(org)) return false
            hasOrganization = true
            return true
        }
    }

    private fun phoneDigits(number: String): String = number.filter { it.isDigit() }

    private fun samePhone(a: String, b: String): Boolean {
        if (a == b) return true
        val (short, long) = if (a.length < b.length) a to b else b to a
        return short.length >= MIN_PHONE_SUFFIX_DIGITS && long.endsWith(short)
    }

    private fun emailKey(address: String): String = address.trim().lowercase()

    private fun addressKey(address: PostalAddress): String =
        listOf(
            address.poBox,
            address.neighborhood,
            address.street,
            address.city,
            address.region,
            address.postcode,
            address.country
        ).joinToString("|") { it.orEmpty().trim().lowercase() }

    private fun imKey(im: ImAccount): String =
        "${im.protocol}|${im.customProtocol.orEmpty().lowercase()}|${im.handle.trim().lowercase()}"

    private fun hasContent(org: Organization): Boolean =
        !org.company.isNullOrBlank() || !org.department.isNullOrBlank() || !org.title.isNullOrBlank()
}
