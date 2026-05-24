// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.protoncontacts

import ezvcard.Ezvcard
import ezvcard.VCard
import ezvcard.VCardVersion
import ezvcard.parameter.AddressType
import ezvcard.parameter.EmailType
import ezvcard.parameter.TelephoneType
import ezvcard.property.Address
import ezvcard.property.Email
import ezvcard.property.FormattedName
import ezvcard.property.Impp
import ezvcard.property.Note
import ezvcard.property.Organization
import ezvcard.property.Photo
import ezvcard.property.StructuredName
import ezvcard.property.Telephone
import ezvcard.property.Title
import ezvcard.property.Uid
import io.pcontacts.core.logging.Logger
import io.pcontacts.core.logging.NoOpSink
import io.pcontacts.core.logging.RedactingLogger
import io.pcontacts.core.proton.api.contacts.ContactCardDto
import java.net.URI

/**
 * Inverse of [ContactDecrypter] + [VCardMerger]. Takes a
 * [DecryptedContact] and produces a list of [ContactCardDto] ready
 * for the Proton write API.
 *
 * Card topology follows ADR-0017 §2 (Choice 2B):
 *   - Card 1: SIGNED (type 2) — vCard with `FN`, `UID`, `VERSION`.
 *   - Card 2: ENCRYPTED_AND_SIGNED (type 3) — vCard with all other
 *     properties (`N`, `EMAIL`, `TEL`, `ADR`, `ORG`, `TITLE`,
 *     `NOTE`, `IMPP`, `PHOTO`).
 *
 * The [encryptOp] seam is wired to `:core:crypto` in production and
 * to a pass-through lambda in tests.
 */
class ContactSerializer(
    private val encryptOp: CardEncryptOp,
    private val logger: Logger = RedactingLogger(tag = "ContactSerialize", sink = NoOpSink)
) {

    fun serialize(contact: DecryptedContact): List<ContactCardDto> {
        val signedVCard = buildSignedCard(contact)
        val encryptedVCard = buildEncryptedCard(contact)

        val signedText = writeVCard(signedVCard)
        val encryptedText = writeVCard(encryptedVCard)

        val signedOutcome = encryptOp(CardEncryptRequest.SignOnly(signedText))
        val encryptedOutcome = encryptOp(CardEncryptRequest.EncryptAndSign(encryptedText))

        return listOf(
            ContactCardDto(
                type = CardType.SIGNED.wireValue,
                data = signedOutcome.data,
                signature = signedOutcome.signature
            ),
            ContactCardDto(
                type = CardType.ENCRYPTED_AND_SIGNED.wireValue,
                data = encryptedOutcome.data,
                signature = encryptedOutcome.signature
            )
        )
    }

    private fun buildSignedCard(contact: DecryptedContact): VCard {
        val vcard = VCard()
        val fn = contact.fullName?.takeIf { it.isNotBlank() }
            ?: contact.emails.firstOrNull()?.address
            ?: "Unknown"
        vcard.setFormattedName(FormattedName(fn))

        if (contact.protonUid != null) {
            vcard.uid = Uid(contact.protonUid)
        }

        return vcard
    }

    private fun buildEncryptedCard(contact: DecryptedContact): VCard {
        val vcard = VCard()

        buildStructuredName(contact)?.let { vcard.structuredName = it }
        contact.emails.forEach { e -> vcard.addEmail(buildEmail(e)) }
        contact.phones.forEach { p -> vcard.addTelephoneNumber(buildPhone(p)) }
        contact.addresses.forEach { a -> vcard.addAddress(buildAddress(a)) }
        buildOrganization(contact.organization)?.let { vcard.addOrganization(it) }
        contact.organization?.title?.let { vcard.addTitle(Title(it)) }
        contact.notes.forEach { n -> vcard.addNote(Note(n)) }
        contact.imAccounts.forEach { im -> buildImpp(im)?.let { vcard.addImpp(it) } }
        buildPhoto(contact.photo)?.let { vcard.addPhoto(it) }

        return vcard
    }

    private fun buildStructuredName(contact: DecryptedContact): StructuredName? {
        val sn = contact.structuredName ?: return null
        return StructuredName().apply {
            given = sn.given
            family = sn.family
            sn.additionalNames.forEach { additionalNames.add(it) }
            sn.prefixes.forEach { prefixes.add(it) }
            sn.suffixes.forEach { suffixes.add(it) }
        }
    }

    private fun buildEmail(email: DecryptedEmail): Email {
        val e = Email(email.address)
        email.types.forEach { t ->
            val type = EmailType.find(t)
            if (type != null) e.types.add(type)
        }
        if (email.isPrimary) e.pref = 1
        return e
    }

    private fun buildPhone(phone: DecryptedPhone): Telephone {
        val t = Telephone(phone.number)
        phone.types.forEach { token ->
            val type = TelephoneType.find(token)
            if (type != null) t.types.add(type)
        }
        if (phone.isPrimary) t.pref = 1
        return t
    }

    private fun buildAddress(addr: DecryptedAddress): Address {
        val a = Address()
        a.poBox = addr.poBox
        a.extendedAddress = addr.extendedAddress
        a.streetAddress = addr.street
        a.locality = addr.locality
        a.region = addr.region
        a.postalCode = addr.postalCode
        a.country = addr.country
        addr.types.forEach { token ->
            val type = AddressType.find(token)
            if (type != null) a.types.add(type)
        }
        if (addr.isPrimary) a.pref = 1
        return a
    }

    private fun buildOrganization(org: DecryptedOrganization?): Organization? {
        org ?: return null
        if (org.company == null && org.department == null) return null
        val o = Organization()
        org.company?.let { o.values.add(it) }
        org.department?.let {
            if (o.values.isEmpty()) o.values.add("")
            o.values.add(it)
        }
        return o
    }

    private fun buildImpp(im: DecryptedIm): Impp? {
        val scheme = im.protocol?.takeIf { it.isNotBlank() } ?: return null
        val handle = im.handle.takeIf { it.isNotBlank() } ?: return null
        return try {
            Impp(URI("$scheme:$handle"))
        } catch (_: Exception) {
            logger.warn { "skipping malformed IMPP URI scheme=$scheme" }
            null
        }
    }

    private fun buildPhoto(photo: DecryptedPhoto?): Photo? {
        photo ?: return null
        if (photo.data.isEmpty()) return null
        return Photo(photo.data, null)
    }

    private fun writeVCard(vcard: VCard): String =
        Ezvcard.write(vcard).version(VCardVersion.V4_0).prodId(false).go().trimEnd()
}
