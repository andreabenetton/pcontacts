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

    // ---- repeated entries of one key (ADR-0017, 2026-10-14) ----

    @Test fun a_key_whose_entries_changed_carries_its_whole_new_group_as_modified() {
        val from = contact(phones = listOf(DecryptedPhone("1", listOf("home")), DecryptedPhone("1", listOf("work"))))
        val to = contact(phones = listOf(DecryptedPhone("1", listOf("home")), DecryptedPhone("1", listOf("cell"))))

        val patch = ContactPatch.diff(from, to)

        assertEquals(listOf(listOf("home"), listOf("cell")), patch.phones.modified.map { it.types })
        assertTrue(patch.phones.added.isEmpty() && patch.phones.removed.isEmpty())
    }

    @Test fun order_and_exact_repeats_within_a_key_are_no_change() {
        val home = DecryptedPhone("1", listOf("home"))
        val work = DecryptedPhone("1", listOf("work"))

        val patch = ContactPatch.diff(contact(phones = listOf(home, work)), contact(phones = listOf(work, home, home)))

        assertTrue(patch.isEmpty)
    }

    @Test fun a_repeated_email_diffs_as_one_address_primary_if_any_repeat_is() {
        val from = contact(emails = listOf(DecryptedEmail("a@x", isPrimary = true), DecryptedEmail("a@x")))
        val to = contact(emails = listOf(DecryptedEmail("a@x")))

        val patch = ContactPatch.diff(from, to)

        assertEquals(listOf(DecryptedEmail("a@x", isPrimary = false)), patch.emails.modified)
    }

    @Test fun dropping_an_email_repeat_is_no_change() {
        val primary = DecryptedEmail("a@x", isPrimary = true)
        val from = contact(emails = listOf(primary, DecryptedEmail("b@x"), DecryptedEmail("a@x")))
        val to = contact(emails = listOf(primary, DecryptedEmail("b@x")))

        assertTrue(ContactPatch.diff(from, to).isEmpty)
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

    @Test fun the_new_fields_are_diffed_as_whole_values() {
        val from = DecryptedContact.empty("c1").copy(birthday = "1990-03-12", websites = listOf("https://a.example"))
        val to = from.copy(birthday = null, nicknames = listOf("Ali"))

        val patch = ContactPatch.diff(from, to)

        assertEquals(ContactPatch.Change<String?>(null), patch.birthday)
        assertEquals(ContactPatch.Change(listOf("Ali")), patch.nicknames)
        assertNull(patch.websites)
        assertNull(patch.anniversary)
        assertTrue(ContactPatch.diff(from, from).isEmpty)
    }
}
