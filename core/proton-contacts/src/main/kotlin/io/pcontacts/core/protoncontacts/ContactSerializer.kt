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
import java.util.UUID

/**
 * Inverse of [ContactDecrypter] + [VCardMerger]: produces the
 * [ContactCardDto]s for the Proton write API.
 *
 * Two entry points (ADR-0017 §2):
 *   - [serialize] `(contact)` — a **create** builds the cards from
 *     scratch (Choice 2B): a SIGNED card with `FN`, `UID` and `EMAIL`
 *     (where Proton's client keeps them, `[V]` WebClients
 *     `packages/shared/lib/contacts/constants.ts`) and an
 *     ENCRYPTED_AND_SIGNED card with everything else.
 *   - [serialize] `(carrier, patch, fallbackUid)` — an **update**
 *     patches the contact's current cards (Choice 2C, [CardPatcher]):
 *     only the changed owned properties move; everything else on the
 *     server's cards is written back untouched.
 *
 * The [encryptOp] seam is wired to `:core:crypto` in production and
 * to a pass-through lambda in tests.
 */
class ContactSerializer(
    private val encryptOp: CardEncryptOp,
    private val logger: Logger = RedactingLogger(tag = "ContactSerialize", sink = NoOpSink)
) {

    /** Update: the carrier's cards with [patch] applied; a CLEAR_TEXT card is passed through unsigned. */
    fun serialize(carrier: List<DecryptedCard>, patch: ContactPatch, fallbackUid: String): List<ContactCardDto> {
        val patcher = CardPatcher(carrier, fallbackUid)
        patcher.apply(patch)
        return patcher.render().map { (type, text) ->
            when (type) {
                CardType.CLEAR_TEXT -> ContactCardDto(type = type.wireValue, data = text, signature = null)
                CardType.SIGNED -> encryptOp(CardEncryptRequest.SignOnly(text)).let {
                    ContactCardDto(type = type.wireValue, data = it.data, signature = it.signature)
                }
                CardType.ENCRYPTED, CardType.ENCRYPTED_AND_SIGNED ->
                    encryptOp(CardEncryptRequest.EncryptAndSign(text)).let {
                        ContactCardDto(
                            type = CardType.ENCRYPTED_AND_SIGNED.wireValue,
                            data = it.data,
                            signature = it.signature
                        )
                    }
            }
        }
    }

    /** Create: cards built from scratch. */
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

        // Proton rejects an update whose signed card has no UID (400 Code
        // 2002, "UID Field is missing"). Contacts imported without a vCard
        // UID reach us with a null protonUid, so fall back to a stable UID
        // derived from the contact id — deterministic, so repeated syncs of
        // the same contact don't churn its UID.
        vcard.uid = Uid(contact.protonUid?.takeIf { it.isNotBlank() } ?: fallbackUid(contact.protonContactId))
        // [V] Proton keeps EMAIL in the signed card; [A] the server derives
        // ContactEmails (autocomplete, `contacts/emails`) from it.
        contact.emails.forEach { e -> vcard.addEmail(buildEmail(e)) }

        return vcard
    }

    private fun buildEncryptedCard(contact: DecryptedContact): VCard {
        val vcard = VCard()

        buildStructuredName(contact)?.let { vcard.structuredName = it }
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

    companion object {
        /**
         * The UID a create mints for a contact that has none: stable per
         * local id, so a retried create after a lost response can be
         * recognised on the server by this value (ADR-0017 §5).
         */
        fun fallbackUid(protonContactId: String): String =
            "urn:uuid:${UUID.nameUUIDFromBytes(protonContactId.toByteArray())}"
    }
}
