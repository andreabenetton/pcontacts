// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.contactswriter

/**
 * Pure diff for ADR-0023: which fields on linked RawContacts are
 * missing from the Proton copy. Values already on the Proton row are
 * never offered; the same value seen on several siblings (a phone
 * stored by both WhatsApp and the SIM) is offered once, attributed to
 * every account that carries it.
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
        val collector = Collector(proton)
        for ((accountType, row) in siblings) collector.offer(accountType, row)
        return collector.result()
    }

    /**
     * Keys already accounted for, seeded from Proton (never offered) and
     * grown by each sibling (offered once, sources accumulated).
     */
    private class Collector(proton: ContactRow?) {
        private class Entry(val field: LinkedField, val sources: MutableList<String?>)

        private val offered = mutableListOf<Entry>()

        // Value key → index into [offered]; -1 marks a value Proton already has.
        private val phones = proton?.phones.orEmpty().map { phoneDigits(it.number) to -1 }.toMutableList()
        private val emails = seed(proton?.emails.orEmpty().map(::emailKey))
        private val addresses = seed(proton?.addresses.orEmpty().map(::addressKey))
        private val notes = seed(proton?.notes.orEmpty().map { it.trim() })
        private val ims = seed(proton?.imAccounts.orEmpty().map(::imKey))
        private var organization = if (proton?.organization?.let(::hasContent) == true) -1 else null

        // Imported fields are appended, never promoted: drop the sibling's primary flag.
        fun offer(accountType: String?, row: ContactRow) {
            for (phone in row.phones) offerPhone(accountType, phone)
            for (email in row.emails) offer(emails, emailKey(email), accountType) { LinkedField.EmailAddress(email) }
            for (address in row.addresses) {
                offer(addresses, addressKey(address), accountType) {
                    LinkedField.Address(address.copy(isPrimary = false))
                }
            }
            row.organization?.takeIf(::hasContent)?.let { org ->
                organization = attribute(organization, accountType) { LinkedField.Org(org) }
            }
            for (note in row.notes) {
                if (note.isNotBlank()) offer(notes, note.trim(), accountType) { LinkedField.NoteText(note) }
            }
            for (im in row.imAccounts) offer(ims, imKey(im), accountType) { LinkedField.Im(im) }
        }

        fun result(): List<LinkedFieldCandidate> =
            offered.map { LinkedFieldCandidate(it.field, it.sources.toList()) }

        private fun offerPhone(accountType: String?, phone: PhoneEntry) {
            val digits = phoneDigits(phone.number)
            if (digits.isEmpty()) return
            val match = phones.indexOfFirst { samePhone(it.first, digits) }
            val idx = attribute(phones.getOrNull(match)?.second, accountType) {
                LinkedField.PhoneNumber(phone.copy(isPrimary = false))
            }
            if (match < 0) phones += digits to idx
        }

        private fun offer(
            index: MutableMap<String, Int>,
            key: String,
            accountType: String?,
            field: () -> LinkedField
        ) {
            index[key] = attribute(index[key], accountType, field)
        }

        /** Returns the entry index for a value: unchanged when Proton has it, new or updated otherwise. */
        private fun attribute(existing: Int?, accountType: String?, field: () -> LinkedField): Int {
            if (existing == -1) return -1
            if (existing == null) {
                offered += Entry(field(), mutableListOf(accountType))
                return offered.lastIndex
            }
            val sources = offered[existing].sources
            if (accountType !in sources) sources += accountType
            return existing
        }

        private fun seed(keys: List<String>): MutableMap<String, Int> =
            keys.associateWith { -1 }.toMutableMap()
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
