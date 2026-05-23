// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.protoncontacts

import ezvcard.Ezvcard
import ezvcard.VCard
import ezvcard.parameter.AddressType
import ezvcard.parameter.EmailType
import ezvcard.parameter.ImppType
import ezvcard.parameter.TelephoneType
import ezvcard.property.Uid
import io.pcontacts.core.logging.Logger
import io.pcontacts.core.logging.NoOpSink
import io.pcontacts.core.logging.RedactingLogger

/**
 * Merges all decrypted vCard fragments for one contact into a single
 * vCard-shaped projection.
 *
 * Per Plan §10.3:
 *   - Parse each fragment as vCard 4.0 via ez-vcard.
 *   - Discard `UID` properties whose source card was not SIGNED — a
 *     tampered ENCRYPTED card could otherwise rebind a contact's
 *     identity to a colliding UID.
 *   - All other properties accumulate.
 *
 * Returns a Kotlin-native `DecryptedContact` so :core:proton-contacts
 * owns the public surface and downstream callers don't pick up
 * ez-vcard at compile time.
 */
internal class VCardMerger(
    private val logger: Logger = RedactingLogger(tag = "VCardMerge", sink = NoOpSink)
) {

    fun merge(protonContactId: String, decrypted: List<DecryptedCard>): DecryptedContact {
        if (decrypted.isEmpty()) return DecryptedContact.empty(protonContactId)

        val merged = VCard()
        var malformed = 0

        for (card in decrypted) {
            val fragment = try {
                Ezvcard.parse(card.plaintext).first()
            } catch (t: Throwable) {
                // Malformed plaintext is non-recoverable for this card; log
                // a non-sensitive count (never the plaintext) and continue.
                malformed += 1
                logger.warn(t) { "malformed vCard fragment in card type=${card.originalType}" }
                null
            }
            if (fragment == null) continue
            for (property in fragment.properties) {
                if (property is Uid && card.originalType != CardType.SIGNED) {
                    // §10.3 — discard stray UIDs from non-SIGNED cards.
                    continue
                }
                merged.addProperty(property)
            }
        }

        if (malformed > 0) {
            logger.warn { "$malformed malformed fragment(s) in contact $protonContactId; merged what we could" }
        }

        return project(protonContactId, decrypted, merged)
    }

    private fun project(
        protonContactId: String,
        sourceCards: List<DecryptedCard>,
        merged: VCard
    ): DecryptedContact {
        val vCardUid: String? = merged.uid?.value?.takeIf { it.isNotBlank() }
        val structuredName = projectStructuredName(merged)
        val fullName: String? = merged.formattedName?.value?.takeIf { it.isNotBlank() }
            ?: deriveFnFromN(merged)

        val emails = merged.emails.orEmpty().map { e ->
            DecryptedEmail(
                address = e.value.orEmpty(),
                types = e.types.orEmpty().map(EmailType::getValue),
                // ez-vcard surfaces vCard PREF via getPref(); the highest-pref
                // (lowest-numeric) email is the "primary" in our model.
                isPrimary = (e.pref ?: Int.MAX_VALUE) == 1
            )
        }.filter { it.address.isNotBlank() }

        val phones = merged.telephoneNumbers.orEmpty().map { t ->
            // ez-vcard's Telephone.text is the standard string form (RFC 6350
            // §6.4.1). Telephone.uri is set for the "uri-style" form (tel:);
            // we fall back to it when text is absent.
            val number = t.text?.takeIf { it.isNotBlank() } ?: t.uri?.toString().orEmpty()
            DecryptedPhone(
                number = number,
                types = t.types.orEmpty().map(TelephoneType::getValue),
                isPrimary = (t.pref ?: Int.MAX_VALUE) == 1
            )
        }.filter { it.number.isNotBlank() }

        val addresses = projectAddresses(merged)
        val organization = projectOrganization(merged)
        val notes = merged.notes.orEmpty().mapNotNull { n -> n.value?.takeIf { it.isNotBlank() } }
        val imAccounts = projectImAccounts(merged)
        val photo = projectPhoto(merged)

        // A contact is verified only if every card that should have been
        // signed actually verified.
        val unverified = sourceCards.count {
            !it.verified && (it.originalType == CardType.SIGNED ||
                it.originalType == CardType.ENCRYPTED_AND_SIGNED)
        }

        return DecryptedContact(
            protonContactId = protonContactId,
            protonUid = vCardUid,
            fullName = fullName,
            structuredName = structuredName,
            emails = emails,
            phones = phones,
            addresses = addresses,
            organization = organization,
            notes = notes,
            imAccounts = imAccounts,
            photo = photo,
            verified = unverified == 0,
            cardCount = sourceCards.size,
            unverifiedCardCount = unverified
        )
    }

    /**
     * Reads `N` from the merged vCard. Returns null if N is absent
     * OR every component is blank — the writer treats null as "no
     * structured-name columns to write".
     */
    private fun projectStructuredName(merged: VCard): DecryptedStructuredName? {
        val n = merged.structuredName ?: return null
        val given = n.given?.takeIf { it.isNotBlank() }
        val family = n.family?.takeIf { it.isNotBlank() }
        val additional = n.additionalNames.orEmpty().filter { it.isNotBlank() }
        val prefixes = n.prefixes.orEmpty().filter { it.isNotBlank() }
        val suffixes = n.suffixes.orEmpty().filter { it.isNotBlank() }
        if (given == null && family == null &&
            additional.isEmpty() && prefixes.isEmpty() && suffixes.isEmpty()
        ) {
            return null
        }
        return DecryptedStructuredName(
            given = given,
            family = family,
            additionalNames = additional,
            prefixes = prefixes,
            suffixes = suffixes
        )
    }

    private fun projectAddresses(merged: VCard): List<DecryptedAddress> =
        merged.addresses.orEmpty().mapNotNull { addr ->
            val out = DecryptedAddress(
                poBox = addr.poBox?.takeIf { it.isNotBlank() },
                extendedAddress = addr.extendedAddress?.takeIf { it.isNotBlank() },
                street = addr.streetAddress?.takeIf { it.isNotBlank() },
                locality = addr.locality?.takeIf { it.isNotBlank() },
                region = addr.region?.takeIf { it.isNotBlank() },
                postalCode = addr.postalCode?.takeIf { it.isNotBlank() },
                country = addr.country?.takeIf { it.isNotBlank() },
                types = addr.types.orEmpty().map(AddressType::getValue),
                isPrimary = (addr.pref ?: Int.MAX_VALUE) == 1
            )
            // Drop address entries with no usable component.
            if (listOfNotNull(
                    out.poBox, out.extendedAddress, out.street,
                    out.locality, out.region, out.postalCode, out.country
                ).isEmpty()) null else out
        }

    private fun projectOrganization(merged: VCard): DecryptedOrganization? {
        val org = merged.organization
        val values = org?.values.orEmpty()
        val company = values.getOrNull(0)?.takeIf { it.isNotBlank() }
        val department = values.getOrNull(1)?.takeIf { it.isNotBlank() }
        val title = merged.titles.orEmpty().firstOrNull()?.value?.takeIf { it.isNotBlank() }
        if (company == null && department == null && title == null) return null
        return DecryptedOrganization(company = company, department = department, title = title)
    }

    private fun projectImAccounts(merged: VCard): List<DecryptedIm> =
        merged.impps.orEmpty().mapNotNull { impp ->
            val uri = impp.uri ?: return@mapNotNull null
            // URI form: <scheme>:<scheme-specific-part>. e.g. xmpp:alice@x.
            val scheme = uri.scheme?.takeIf { it.isNotBlank() }
            val handle = uri.schemeSpecificPart?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            DecryptedIm(
                handle = handle,
                protocol = scheme,
                types = impp.types.orEmpty().map(ImppType::getValue)
            )
        }

    private fun projectPhoto(merged: VCard): DecryptedPhoto? {
        // Only the first PHOTO; ContactsContract has one inline photo slot
        // per RawContact. URL-reference photos (no inline data) are skipped
        // for MVP — fetching them is out of scope until task 19+.
        val photo = merged.photos.orEmpty().firstOrNull { it.data?.isNotEmpty() == true } ?: return null
        val data = photo.data ?: return null
        return DecryptedPhoto(
            data = data,
            mimeType = photo.contentType?.mediaType?.takeIf { it.isNotBlank() }
        )
    }

    /**
     * Some vCard fragments only carry structured `N` (family, given, ...);
     * synthesize an FN from the first non-blank component so the contact
     * still has a display name. Mirrors ez-vcard's web-client behavior.
     */
    private fun deriveFnFromN(merged: VCard): String? {
        val n = merged.structuredName ?: return null
        val parts = listOfNotNull(
            n.prefixes?.firstOrNull()?.takeIf { it.isNotBlank() },
            n.given?.takeIf { it.isNotBlank() },
            n.additionalNames?.firstOrNull()?.takeIf { it.isNotBlank() },
            n.family?.takeIf { it.isNotBlank() },
            n.suffixes?.firstOrNull()?.takeIf { it.isNotBlank() }
        )
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }

}
