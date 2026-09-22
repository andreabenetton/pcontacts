// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.protoncontacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactPatchTest {

    private fun contact(
        emails: List<DecryptedEmail> = emptyList(),
        phones: List<DecryptedPhone> = emptyList(),
        addresses: List<DecryptedAddress> = emptyList(),
        notes: List<String> = emptyList(),
        photo: DecryptedPhoto? = null
    ) = DecryptedContact(
        protonContactId = "c",
        protonUid = "u",
        fullName = "Alice",
        emails = emails,
        phones = phones,
        addresses = addresses,
        notes = notes,
        photo = photo,
        verified = true,
        cardCount = 2,
        unverifiedCardCount = 0
    )

    @Test fun identical_contacts_yield_an_empty_patch() {
        val a = contact(emails = listOf(DecryptedEmail("a@x")), phones = listOf(DecryptedPhone("1")))
        assertTrue(ContactPatch.diff(a, a).isEmpty)
    }

    @Test fun lists_diff_by_key_into_added_removed_and_modified() {
        val from = contact(
            emails = listOf(DecryptedEmail("keep@x", isPrimary = true), DecryptedEmail("old@x")),
            phones = listOf(DecryptedPhone("1", listOf("home")))
        )
        val to = contact(
            emails = listOf(DecryptedEmail("keep@x", isPrimary = false), DecryptedEmail("new@x")),
            phones = listOf(DecryptedPhone("1", listOf("cell")))
        )

        val patch = ContactPatch.diff(from, to)

        assertEquals(listOf("new@x"), patch.emails.added.map { it.address })
        assertEquals(listOf("old@x"), patch.emails.removed.map { it.address })
        assertEquals(listOf("keep@x"), patch.emails.modified.map { it.address })
        assertEquals(listOf(listOf("cell")), patch.phones.modified.map { it.types })
        assertNull(patch.fullName)
        assertNull(patch.notes)
    }

    @Test fun addresses_are_identified_by_every_component() {
        val rome = DecryptedAddress(street = "Via Roma 1", locality = "Rome", postalCode = "00100", country = "IT")
        val sameStreetElsewhere = rome.copy(country = "SM")

        val patch = ContactPatch.diff(
            contact(addresses = listOf(rome)),
            contact(addresses = listOf(sameStreetElsewhere))
        )

        assertEquals(listOf(sameStreetElsewhere), patch.addresses.added)
        assertEquals(listOf(rome), patch.addresses.removed)
    }

    @Test fun photo_change_is_detected_by_content_not_identity() {
        val bytes = byteArrayOf(1, 2, 3)
        val same = ContactPatch.diff(
            contact(photo = DecryptedPhoto(bytes)),
            contact(photo = DecryptedPhoto(bytes.copyOf()))
        )
        val removed = ContactPatch.diff(contact(photo = DecryptedPhoto(bytes)), contact())
        val changed = ContactPatch.diff(
            contact(photo = DecryptedPhoto(bytes)),
            contact(photo = DecryptedPhoto(byteArrayOf(9)))
        )

        assertNull(same.photo)
        assertNull(removed.photo?.to)
        assertEquals(byteArrayOf(9).toList(), changed.photo?.to?.data?.toList())
    }
}
