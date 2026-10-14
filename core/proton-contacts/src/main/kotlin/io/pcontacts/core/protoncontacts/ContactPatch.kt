// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.protoncontacts

/**
 * The field-level difference between two contacts, the only thing an
 * update writes onto the server's cards (ADR-0017 §2, Choice 2C). It
 * names exactly the owned properties that changed; everything else on
 * the cards is left alone by [ContactSerializer].
 *
 * Multi-valued properties are keyed the way the three-way merger keys
 * them, so a change the merge saw is a change the patch carries:
 * emails by address, phones by number, addresses by their full
 * component tuple, IM accounts by handle and protocol. A key's
 * entries are compared as a group, a set: order and exact repeats are
 * no change, and an email counts once per address. A key present on
 * both sides whose group changed carries its **whole** new group in
 * `modified`, so the card's properties for it are replaced as a whole
 * and none is silently rewritten (ADR-0017, 2026-10-14).
 */
data class ContactPatch(
    val fullName: Change<String?>? = null,
    val structuredName: Change<DecryptedStructuredName?>? = null,
    val emails: ListPatch<DecryptedEmail> = ListPatch(),
    val phones: ListPatch<DecryptedPhone> = ListPatch(),
    val addresses: ListPatch<DecryptedAddress> = ListPatch(),
    val imAccounts: ListPatch<DecryptedIm> = ListPatch(),
    val organization: Change<DecryptedOrganization?>? = null,
    val notes: Change<List<String>>? = null,
    val birthday: Change<String?>? = null,
    val anniversary: Change<String?>? = null,
    val nicknames: Change<List<String>>? = null,
    val websites: Change<List<String>>? = null,
    /** New photo bytes, or a change to null to remove the inline photo. */
    val photo: Change<DecryptedPhoto?>? = null
) {
    data class Change<T>(val to: T)

    data class ListPatch<T>(
        val added: List<T> = emptyList(),
        val removed: List<T> = emptyList(),
        val modified: List<T> = emptyList()
    ) {
        val isEmpty: Boolean get() = added.isEmpty() && removed.isEmpty() && modified.isEmpty()
    }

    val isEmpty: Boolean
        get() = fullName == null && structuredName == null && organization == null && notes == null &&
            photo == null && emails.isEmpty && phones.isEmpty && addresses.isEmpty && imAccounts.isEmpty &&
            birthday == null && anniversary == null && nicknames == null && websites == null

    companion object {
        fun emailKey(e: DecryptedEmail): String = e.address
        fun phoneKey(p: DecryptedPhone): String = p.number
        fun addressKey(a: DecryptedAddress): String =
            listOf(a.poBox, a.extendedAddress, a.street, a.locality, a.region, a.postalCode, a.country)
                .joinToString("|") { it.orEmpty() }
        fun imKey(im: DecryptedIm): String = "${im.handle}|${im.protocol}"

        /** One key's entries on two sides agree when they hold the same values: order and exact repeats are no change. */
        fun <T> sameEntries(a: List<T>?, b: List<T>?): Boolean = a?.toSet() == b?.toSet()

        /**
         * One entry per address, primary if any repeat is: the row projection keeps nothing else
         * of an email, so repeats left by a signed and an encrypted card are one address.
         */
        fun emailsByAddress(emails: List<DecryptedEmail>): List<DecryptedEmail> =
            emails.groupBy(::emailKey).values.map { group ->
                group.first().copy(isPrimary = group.any { it.isPrimary })
            }

        /** What has to change on [from] to become [to]. */
        fun diff(from: DecryptedContact, to: DecryptedContact): ContactPatch = ContactPatch(
            fullName = scalar(from.fullName, to.fullName),
            structuredName = scalar(from.structuredName, to.structuredName),
            emails = list(emailsByAddress(from.emails), emailsByAddress(to.emails), ::emailKey),
            phones = list(from.phones, to.phones, ::phoneKey),
            addresses = list(from.addresses, to.addresses, ::addressKey),
            imAccounts = list(from.imAccounts, to.imAccounts, ::imKey),
            organization = scalar(from.organization, to.organization),
            notes = scalar(from.notes, to.notes),
            birthday = scalar(from.birthday, to.birthday),
            anniversary = scalar(from.anniversary, to.anniversary),
            nicknames = scalar(from.nicknames, to.nicknames),
            websites = scalar(from.websites, to.websites),
            photo = if (photoHash(from.photo) == photoHash(to.photo)) null else Change(to.photo)
        )

        private fun <T> scalar(from: T, to: T): Change<T>? = if (from == to) null else Change(to)

        private fun <T, K> list(from: List<T>, to: List<T>, key: (T) -> K): ListPatch<T> {
            val fromByKey = from.groupBy(key)
            val toByKey = to.groupBy(key)
            val changed = toByKey.filter { (k, group) -> k in fromByKey && !sameEntries(fromByKey[k], group) }.keys
            return ListPatch(
                added = to.filter { key(it) !in fromByKey },
                removed = from.filter { key(it) !in toByKey },
                modified = to.filter { key(it) in changed }
            )
        }

        private fun photoHash(photo: DecryptedPhoto?): String? = photo?.let { PhotoHash.of(it.data) }
    }
}
