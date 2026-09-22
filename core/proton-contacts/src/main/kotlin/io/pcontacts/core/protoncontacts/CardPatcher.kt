// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.protoncontacts

import ezvcard.Ezvcard
import ezvcard.VCard
import ezvcard.VCardVersion
import ezvcard.parameter.AddressType
import ezvcard.parameter.ImageType
import ezvcard.parameter.TelephoneType
import ezvcard.property.Address
import ezvcard.property.Categories
import ezvcard.property.Email
import ezvcard.property.FormattedName
import ezvcard.property.Impp
import ezvcard.property.Key
import ezvcard.property.Note
import ezvcard.property.Organization
import ezvcard.property.Photo
import ezvcard.property.ProductId
import ezvcard.property.RawProperty
import ezvcard.property.StructuredName
import ezvcard.property.Telephone
import ezvcard.property.Title
import ezvcard.property.Uid
import ezvcard.property.VCardProperty
import java.net.URI

/**
 * Applies a [ContactPatch] onto the contact's current cards (ADR-0017
 * §2, Choice 2C). Every property the app does not own stays where it
 * was, with its parameters and group; owned properties are edited in
 * place so their parameters survive too. Removing a property removes
 * its group-mates (`itemN.KEY`, `itemN.X-PM-*`) so no later property
 * inherits them.
 *
 * Placement of new properties follows Proton's own client: `[V]`
 * WebClients `packages/shared/lib/contacts/constants.ts` keeps `FN`,
 * `UID`, `EMAIL` and the key fields in the SIGNED card and everything
 * else in the ENCRYPTED_AND_SIGNED card, with only `CATEGORIES` in the
 * CLEAR_TEXT card. A carrier that breaks those rules is rebuilt to them
 * before the patch: an `EMAIL` in an encrypted card (written by earlier
 * pcontacts versions) moves to the signed card, and a contact Proton
 * created by itself — auto-saved from a sent mail — arrives as one
 * CLEAR_TEXT card holding `FN`, `UID` and `EMAIL`, which `[A]` the
 * server refuses to take back in that card (Code 2001, seen live on
 * 2026-09-22): its `FN`, `UID`, `EMAIL` and key fields move to the signed
 * card and anything else to the encrypted one. `UID` is never minted
 * anew while a card carries one; a second one is dropped, as the merger
 * drops it on read.
 */
internal class CardPatcher(carrier: List<DecryptedCard>, private val fallbackUid: String) {

    /** One card under edit; ENCRYPTED (unsigned) cards are re-emitted signed, never less. */
    class Card(val type: CardType, val vcard: VCard)

    val cards: MutableList<Card> = carrier.map { card ->
        val parsed = Ezvcard.parse(card.plaintext).first()
            ?: error("carrier card unparsable type=${card.originalType}")
        val type = if (card.originalType == CardType.ENCRYPTED) CardType.ENCRYPTED_AND_SIGNED else card.originalType
        Card(type, parsed)
    }.toMutableList()

    // A card made here is vCard 4.0 like everything Proton's client writes; ez-vcard's
    // default is 3.0, which turns PREF=1 into TYPE=pref and which Proton refuses (Code 2001).
    private val signed: Card
        get() = cards.firstOrNull { it.type == CardType.SIGNED }
            ?: Card(CardType.SIGNED, VCard(VCardVersion.V4_0)).also { cards += it }

    private val encrypted: Card
        get() = cards.firstOrNull { it.type == CardType.ENCRYPTED_AND_SIGNED }
            ?: Card(CardType.ENCRYPTED_AND_SIGNED, VCard(VCardVersion.V4_0)).also { cards += it }

    init {
        normalise()
    }

    /**
     * Signed card owns `FN`, `UID`, the emails and the key fields; the
     * clear card owns nothing but `CATEGORIES`. Every EMAIL in the signed
     * card gets an `itemN` group when it has none — `[V]` Proton's client
     * always groups them and `[A]` the server refuses an ungrouped one
     * (Code 2001, seen live on 2026-09-22).
     */
    private fun normalise() {
        // The signed and encrypted cards may be created on the way; walk a snapshot.
        for (card in cards.toList()) {
            if (card.type == CardType.SIGNED) continue
            card.vcard.properties.filter { it.belongsToSignedCard() }.forEach { p ->
                card.vcard.removeProperty(p)
                val taken = (p is Uid && signed.vcard.uid != null) || (p is FormattedName && signed.vcard.formattedName != null)
                if (!taken) signed.vcard.addProperty(p)
            }
            if (card.type == CardType.CLEAR_TEXT) {
                card.vcard.properties.filter { it !is Categories && it !is ProductId }.forEach { p ->
                    card.vcard.removeProperty(p)
                    encrypted.vcard.addProperty(p)
                }
            }
        }
        signed.vcard.emails.filter { it.group.isNullOrBlank() }.forEach { it.group = freshGroup() }
        if (signed.vcard.uid?.value.isNullOrBlank()) signed.vcard.uid = Uid(fallbackUid)
    }

    /** `[V]` constants.ts SIGNED_FIELDS: fn, uid, email and the per-email key fields (`KEY`, `X-PM-*`). */
    private fun VCardProperty.belongsToSignedCard(): Boolean =
        this is FormattedName || this is Uid || this is Email || this is Key ||
            (this is RawProperty && propertyName.startsWith("X-PM-", ignoreCase = true))

    fun apply(patch: ContactPatch) {
        patch.fullName?.let { applyFullName(it.to) }
        patch.structuredName?.let { applyStructuredName(it.to) }
        applyEmails(patch.emails)
        applyPhones(patch.phones)
        applyAddresses(patch.addresses)
        applyIms(patch.imAccounts)
        patch.organization?.let { applyOrganization(it.to) }
        patch.notes?.let { applyNotes(it.to) }
        patch.photo?.let { applyPhoto(it.to) }
    }

    /** The cards to upload: the signed card always, the others only while they still say something. */
    fun render(): List<Pair<CardType, String>> = cards
        .filter { it.type == CardType.SIGNED || it.vcard.properties.any { p -> p !is ProductId } }
        .map { card ->
            val version = card.vcard.version ?: VCardVersion.V4_0
            card.type to Ezvcard.write(card.vcard).version(version).versionStrict(false).prodId(false).go().trimEnd()
        }

    // ---- scalars --------------------------------------------------------

    private fun applyFullName(value: String?) {
        val fn = value?.takeIf { it.isNotBlank() }
            ?: allEmails().firstOrNull()?.value
            ?: signed.vcard.formattedName?.value
            ?: "Unknown"
        val existing = cards.firstNotNullOfOrNull { it.vcard.formattedName }
        if (existing != null) existing.value = fn else signed.vcard.setFormattedName(FormattedName(fn))
    }

    private fun applyStructuredName(value: DecryptedStructuredName?) {
        val existing = cards.firstNotNullOfOrNull { it.vcard.structuredName }
        if (value == null) {
            existing?.let { n -> cards.forEach { it.vcard.removeProperty(n) } }
            return
        }
        val n = existing ?: StructuredName().also { encrypted.vcard.addProperty(it) }
        n.given = value.given
        n.family = value.family
        replaceHead(n.additionalNames, value.additionalNames)
        replaceHead(n.prefixes, value.prefixes)
        replaceHead(n.suffixes, value.suffixes)
    }

    /** The projection keeps only the first element of a list; edit that one and keep the tail. */
    private fun replaceHead(target: MutableList<String>, wanted: List<String>) {
        when {
            wanted.isEmpty() -> target.clear()
            target.isEmpty() -> target.addAll(wanted)
            else -> {
                target[0] = wanted[0]
                if (wanted.size > 1) {
                    target.subList(1, target.size).clear()
                    target.addAll(wanted.drop(1))
                }
            }
        }
    }

    private fun applyOrganization(value: DecryptedOrganization?) {
        val org = cards.firstNotNullOfOrNull { it.vcard.organization }
        val title = cards.firstNotNullOfOrNull { it.vcard.titles.firstOrNull() }
        if (value == null || (value.company == null && value.department == null)) {
            org?.let { o -> cards.forEach { it.vcard.removeProperty(o) } }
        } else {
            val o = org ?: Organization().also { encrypted.vcard.addProperty(it) }
            while (o.values.size < 2) o.values.add("")
            o.values[0] = value.company.orEmpty()
            o.values[1] = value.department.orEmpty()
            if (o.values.size == 2 && o.values[1].isEmpty()) o.values.removeAt(1)
        }
        val wantedTitle = value?.title?.takeIf { it.isNotBlank() }
        when {
            wantedTitle == null -> title?.let { t -> cards.forEach { it.vcard.removeProperty(t) } }
            title != null -> title.value = wantedTitle
            else -> encrypted.vcard.addTitle(Title(wantedTitle))
        }
    }

    private fun applyNotes(wanted: List<String>) {
        val existing = cards.flatMap { c -> c.vcard.notes.map { c to it } }
        existing.filter { (_, n) -> n.value !in wanted }.forEach { (c, n) -> c.vcard.removeProperty(n) }
        val present = existing.map { it.second.value }.toSet()
        wanted.filter { it !in present }.forEach { encrypted.vcard.addNote(Note(it)) }
    }

    private fun applyPhoto(value: DecryptedPhoto?) {
        val inline = cards.flatMap { c -> c.vcard.photos.filter { it.data != null }.map { c to it } }
        if (value == null || value.data.isEmpty()) {
            inline.forEach { (c, p) -> c.vcard.removeProperty(p) }
            return
        }
        val type = value.mimeType?.let { ImageType.get(null, it, null) } ?: inline.firstOrNull()?.second?.contentType
        inline.forEach { (c, p) -> c.vcard.removeProperty(p) }
        (inline.firstOrNull()?.first ?: encrypted).vcard.addPhoto(Photo(value.data, type))
    }

    // ---- multi-valued ---------------------------------------------------

    private fun allEmails(): List<Email> = cards.flatMap { it.vcard.emails }

    private fun applyEmails(patch: ContactPatch.ListPatch<DecryptedEmail>) {
        patch.removed.forEach { e -> allEmails().filter { it.value == e.address }.forEach(::removeWithGroup) }
        patch.modified.forEach { e ->
            allEmails().filter { it.value == e.address }.forEach { it.pref = if (e.isPrimary) 1 else null }
        }
        patch.added.forEach { e ->
            val email = Email(e.address)
            email.group = freshGroup()
            if (e.isPrimary) email.pref = 1
            signed.vcard.addEmail(email)
        }
    }

    private fun applyPhones(patch: ContactPatch.ListPatch<DecryptedPhone>) {
        fun matches(t: Telephone, number: String) = (t.text ?: t.uri?.toString()) == number
        val all = { cards.flatMap { it.vcard.telephoneNumbers } }
        patch.removed.forEach { p -> all().filter { matches(it, p.number) }.forEach(::removeWithGroup) }
        patch.modified.forEach { p ->
            all().filter { matches(it, p.number) }.forEach { t ->
                t.types.removeAll { it.value.lowercase() in ANDROID_TEL_TOKENS }
                p.types.forEach { token -> t.types.add(TelephoneType.get(token)) }
                t.pref = if (p.isPrimary) 1 else null
            }
        }
        patch.added.forEach { p ->
            val t = Telephone(p.number)
            p.types.forEach { token -> t.types.add(TelephoneType.get(token)) }
            if (p.isPrimary) t.pref = 1
            encrypted.vcard.addTelephoneNumber(t)
        }
    }

    private fun applyAddresses(patch: ContactPatch.ListPatch<DecryptedAddress>) {
        fun key(a: Address) = ContactPatch.addressKey(a.toDecrypted())
        val all = { cards.flatMap { it.vcard.addresses } }
        patch.removed.forEach { a -> all().filter { key(it) == ContactPatch.addressKey(a) }.forEach(::removeWithGroup) }
        patch.modified.forEach { a ->
            all().filter { key(it) == ContactPatch.addressKey(a) }.forEach { addr ->
                addr.types.removeAll { it.value.lowercase() in ANDROID_ADR_TOKENS }
                a.types.forEach { token -> addr.types.add(AddressType.get(token)) }
                addr.pref = if (a.isPrimary) 1 else null
            }
        }
        patch.added.forEach { a ->
            val addr = Address()
            addr.poBox = a.poBox
            addr.extendedAddress = a.extendedAddress
            addr.streetAddress = a.street
            addr.locality = a.locality
            addr.region = a.region
            addr.postalCode = a.postalCode
            addr.country = a.country
            a.types.forEach { token -> addr.types.add(AddressType.get(token)) }
            if (a.isPrimary) addr.pref = 1
            encrypted.vcard.addAddress(addr)
        }
    }

    private fun applyIms(patch: ContactPatch.ListPatch<DecryptedIm>) {
        fun key(i: Impp) = "${i.uri?.schemeSpecificPart}|${i.uri?.scheme}"
        val all = { cards.flatMap { it.vcard.impps } }
        patch.removed.forEach { im -> all().filter { key(it) == ContactPatch.imKey(im) }.forEach(::removeWithGroup) }
        patch.added.forEach { im ->
            val scheme = im.protocol?.takeIf { it.isNotBlank() } ?: return@forEach
            val impp = try {
                Impp(URI("$scheme:${im.handle}"))
            } catch (_: IllegalArgumentException) {
                return@forEach
            }
            encrypted.vcard.addImpp(impp)
        }
    }

    // ---- helpers --------------------------------------------------------

    private fun removeWithGroup(property: VCardProperty) {
        val group = property.group
        for (card in cards) {
            card.vcard.removeProperty(property)
            if (group != null) {
                card.vcard.properties.filter { it.group == group }.forEach { card.vcard.removeProperty(it) }
            }
        }
    }

    private fun freshGroup(): String {
        val max = cards.flatMap { it.vcard.properties }
            .mapNotNull { it.group }
            .mapNotNull { GROUP_PATTERN.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }
            .maxOrNull() ?: 0
        return "item${max + 1}"
    }

    private fun Address.toDecrypted() = DecryptedAddress(
        poBox = poBox?.takeIf { it.isNotBlank() },
        extendedAddress = extendedAddress?.takeIf { it.isNotBlank() },
        street = streetAddress?.takeIf { it.isNotBlank() },
        locality = locality?.takeIf { it.isNotBlank() },
        region = region?.takeIf { it.isNotBlank() },
        postalCode = postalCode?.takeIf { it.isNotBlank() },
        country = country?.takeIf { it.isNotBlank() }
    )

    private companion object {
        /** The TEL TYPE tokens Android can express; any other token on the property is not ours to touch. */
        val ANDROID_TEL_TOKENS = setOf("home", "work", "cell", "mobile", "fax", "pager", "main")
        val ANDROID_ADR_TOKENS = setOf("home", "work")
        val GROUP_PATTERN = Regex("item(\\d+)", RegexOption.IGNORE_CASE)
    }
}
