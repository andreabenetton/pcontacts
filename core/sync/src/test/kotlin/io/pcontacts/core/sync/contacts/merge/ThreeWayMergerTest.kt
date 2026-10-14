// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.sync.contacts.merge

import io.pcontacts.core.protoncontacts.ContactPatch
import io.pcontacts.core.protoncontacts.DecryptedAddress
import io.pcontacts.core.protoncontacts.DecryptedContact
import io.pcontacts.core.protoncontacts.DecryptedEmail
import io.pcontacts.core.protoncontacts.DecryptedOrganization
import io.pcontacts.core.protoncontacts.DecryptedPhone
import io.pcontacts.core.protoncontacts.DecryptedPhoto
import io.pcontacts.core.protoncontacts.DecryptedStructuredName
import io.pcontacts.core.protoncontacts.PhotoHash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreeWayMergerTest {

    // Test factory: every merge field optional, so the parameter count is by design.
    @Suppress("LongParameterList")
    private fun contact(
        fullName: String? = "Alice",
        emails: List<DecryptedEmail> = listOf(DecryptedEmail("alice@proton.me", isPrimary = true)),
        phones: List<DecryptedPhone> = emptyList(),
        structuredName: DecryptedStructuredName? = null,
        organization: DecryptedOrganization? = null,
        notes: List<String> = emptyList(),
        addresses: List<DecryptedAddress> = emptyList(),
        photo: DecryptedPhoto? = null,
        serverPhotoHash: String? = photo?.let { PhotoHash.of(it.data) },
        localPhotoHash: String? = null
    ) = DecryptedContact(
        protonContactId = "ct-1",
        protonUid = "uid-1",
        fullName = fullName,
        structuredName = structuredName,
        emails = emails,
        phones = phones,
        addresses = addresses,
        organization = organization,
        notes = notes,
        photo = photo,
        serverPhotoHash = serverPhotoHash,
        localPhotoHash = localPhotoHash,
        verified = true,
        cardCount = 2,
        unverifiedCardCount = 0
    )

    // The provider re-encodes photos: the local bytes of photo A differ from the server's bytes of A.
    private val serverA = DecryptedPhoto(byteArrayOf(1, 1, 1))
    private val localA = DecryptedPhoto(byteArrayOf(1, 1, 2))
    private val serverB = DecryptedPhoto(byteArrayOf(2, 2, 2))
    private val localC = DecryptedPhoto(byteArrayOf(3, 3, 3))
    private fun hash(p: DecryptedPhoto) = PhotoHash.of(p.data)

    private fun mergePhotos(base: DecryptedContact, server: DecryptedContact, local: DecryptedContact) =
        ThreeWayMerger.merge(ThreeWayMerger.MergeInput(base, server, local))

    @Test fun photo_changed_on_server_survives_an_unrelated_local_edit() {
        val base = contact(photo = null, serverPhotoHash = hash(serverA), localPhotoHash = hash(localA))
        val server = contact(photo = serverB)
        val local = contact(photo = localA, phones = listOf(DecryptedPhone("+1")))

        val result = mergePhotos(base, server, local) as ThreeWayMerger.MergeResult.AutoMerged

        assertEquals(serverB, result.merged.photo)
        assertEquals(listOf("+1"), result.merged.phones.map { it.number })
    }

    @Test fun photo_changed_locally_wins_when_the_server_kept_its_photo() {
        val base = contact(photo = null, serverPhotoHash = hash(serverA), localPhotoHash = hash(localA))
        val result = mergePhotos(base, contact(photo = serverA), contact(photo = localC))
        assertEquals(localC, (result as ThreeWayMerger.MergeResult.AutoMerged).merged.photo)
    }

    @Test fun photo_changed_on_both_sides_is_a_conflict() {
        val base = contact(photo = null, serverPhotoHash = hash(serverA), localPhotoHash = hash(localA))
        val result = mergePhotos(base, contact(photo = serverB), contact(photo = localC))
        assertTrue(result is ThreeWayMerger.MergeResult.Conflicted)
        assertEquals(listOf("photo"), (result as ThreeWayMerger.MergeResult.Conflicted).conflicts.map { it.fieldName })
    }

    @Test fun photo_deleted_on_server_survives_an_unrelated_local_edit() {
        val base = contact(photo = null, serverPhotoHash = hash(serverA), localPhotoHash = hash(localA))
        val result = mergePhotos(base, contact(photo = null), contact(photo = localA, notes = listOf("n")))
        assertEquals(null, (result as ThreeWayMerger.MergeResult.AutoMerged).merged.photo)
    }

    @Test fun photo_deleted_locally_propagates_when_the_server_kept_its_photo() {
        val base = contact(photo = null, serverPhotoHash = hash(serverA), localPhotoHash = hash(localA))
        val result = mergePhotos(base, contact(photo = serverA), contact(photo = null))
        assertEquals(null, (result as ThreeWayMerger.MergeResult.AutoMerged).merged.photo)
    }

    @Test fun addresses_are_identified_by_every_component_not_just_street_and_city() {
        val rome = DecryptedAddress(street = "Via Roma 1", locality = "Rome", postalCode = "00100", country = "IT")
        val sanMarino = rome.copy(country = "SM")
        val base = contact(addresses = listOf(rome))

        // The server changed the country; the local side did not touch the address.
        val result = mergePhotos(base, contact(addresses = listOf(sanMarino)), contact(addresses = listOf(rome)))

        assertEquals(listOf(sanMarino), (result as ThreeWayMerger.MergeResult.AutoMerged).merged.addresses)
    }

    @Test fun a_changed_address_type_is_a_modification_of_the_same_address() {
        val home = DecryptedAddress(street = "Via Roma 1", locality = "Rome", types = listOf("home"))
        val work = home.copy(types = listOf("work"))

        val result = mergePhotos(
            contact(addresses = listOf(home)),
            contact(addresses = listOf(home)),
            contact(addresses = listOf(work))
        )

        assertEquals(listOf(work), (result as ThreeWayMerger.MergeResult.AutoMerged).merged.addresses)
    }

    // ---- repeated entries of one key merge as a group (ADR-0017, 2026-10-14) ----

    private val home = DecryptedPhone("+1555", listOf("home"))
    private val work = DecryptedPhone("+1555", listOf("work"))
    private val cell = DecryptedPhone("+1555", listOf("cell"))

    private fun mergePhones(base: List<DecryptedPhone>, server: List<DecryptedPhone>, local: List<DecryptedPhone>) =
        mergePhotos(contact(phones = base), contact(phones = server), contact(phones = local))

    private fun ThreeWayMerger.MergeResult.autoMerged() = (this as ThreeWayMerger.MergeResult.AutoMerged).merged

    @Test fun a_number_repeated_on_every_side_is_one_unchanged_group_and_keeps_both_entries() {
        val merged = mergePhones(listOf(home, work), listOf(work, home), listOf(home, work)).autoMerged()

        assertEquals(setOf(home, work), merged.phones.toSet())
        assertEquals(2, merged.phones.size)
    }

    @Test fun a_type_changed_on_one_of_two_entries_of_a_number_carries_the_whole_local_group() {
        val merged = mergePhones(listOf(home, work), listOf(home, work), listOf(home, cell)).autoMerged()

        assertEquals(listOf(home, cell), merged.phones)
    }

    @Test fun a_number_changed_differently_on_each_side_is_a_conflict() {
        val result = mergePhones(listOf(home), listOf(work), listOf(cell))

        assertEquals(listOf("phones"), (result as ThreeWayMerger.MergeResult.Conflicted).conflicts.map { it.fieldName })
        assertEquals(listOf(cell), result.partial.phones)
    }

    @Test fun the_same_number_added_on_both_sides_with_different_types_is_a_conflict() {
        // Before: both entries were kept, and the change set then wrote the phone's type over Proton's.
        val result = mergePhones(emptyList(), listOf(work), listOf(home))

        assertTrue(result is ThreeWayMerger.MergeResult.Conflicted)
    }

    @Test fun the_same_number_added_identically_on_both_sides_merges_once() {
        assertEquals(listOf(home), mergePhones(emptyList(), listOf(home), listOf(home)).autoMerged().phones)
    }

    @Test fun a_second_entry_of_a_number_added_on_the_phone_is_kept() {
        // Before: the repeated key collapsed, the local edit read as a type change and `home` was lost.
        val merged = mergePhones(listOf(home), listOf(home), listOf(home, work)).autoMerged()

        assertEquals(listOf(home, work), merged.phones)
    }

    @Test fun exact_repeats_are_no_change() {
        val server = contact(phones = listOf(home, home))
        val merged = mergePhotos(contact(phones = listOf(home)), server, contact(phones = listOf(home))).autoMerged()

        assertTrue("nothing to write back to Proton", ContactPatch.diff(server, merged).isEmpty)
    }

    @Test fun a_repeated_email_is_one_address_and_keeps_its_primary() {
        val primary = DecryptedEmail("a@x", isPrimary = true)
        val repeat = DecryptedEmail("a@x")
        val merged = mergePhotos(
            contact(emails = listOf(primary, repeat)),
            contact(emails = listOf(primary, repeat)),
            contact(emails = listOf(primary, repeat), notes = listOf("edited"))
        ).autoMerged()

        assertEquals(listOf(primary), merged.emails)
    }

    @Test fun a_server_dropping_an_email_repeat_does_not_conflict_with_a_local_primary_change() {
        val a = DecryptedEmail("a@x", isPrimary = true)
        val b = DecryptedEmail("b@x")
        val result = mergePhotos(
            contact(emails = listOf(a, b, DecryptedEmail("a@x"))),
            contact(emails = listOf(a, b)),
            contact(emails = listOf(b.copy(isPrimary = true), a.copy(isPrimary = false)))
        )

        assertEquals(setOf("b@x"), result.autoMerged().emails.filter { it.isPrimary }.map { it.address }.toSet())
    }

    @Test fun two_entries_of_one_address_with_different_types_merge_as_a_group() {
        val homeAddress = DecryptedAddress(street = "Via Roma 1", locality = "Rome", types = listOf("home"))
        val workAddress = homeAddress.copy(types = listOf("work"))

        val merged = mergePhotos(
            contact(addresses = listOf(homeAddress)),
            contact(addresses = listOf(homeAddress), notes = listOf("server note")),
            contact(addresses = listOf(homeAddress, workAddress))
        ).autoMerged()

        assertEquals(listOf(homeAddress, workAddress), merged.addresses)
        assertEquals(listOf("server note"), merged.notes)
    }

    @Test fun a_local_delete_of_a_group_the_server_edited_keeps_the_server_group_in_the_conflict() {
        val result = mergePhones(listOf(home), listOf(work), emptyList())

        assertEquals(listOf(work), (result as ThreeWayMerger.MergeResult.Conflicted).partial.phones)
    }

    @Test fun a_merged_group_is_stable_as_the_next_base() {
        val merged = mergePhones(listOf(home), listOf(home), listOf(home, work)).autoMerged()
        val server = contact(phones = listOf(work, home))

        val again = ThreeWayMerger.merge(ThreeWayMerger.MergeInput(merged, server, merged)).autoMerged()

        assertTrue(ContactPatch.diff(server, again).isEmpty)
    }

    @Test fun no_changes_auto_merges_to_base() {
        val base = contact()
        val result = ThreeWayMerger.merge(
            ThreeWayMerger.MergeInput(base = base, server = base, local = base)
        )
        assertTrue(result is ThreeWayMerger.MergeResult.AutoMerged)
        val merged = (result as ThreeWayMerger.MergeResult.AutoMerged).merged
        assertEquals("Alice", merged.fullName)
    }

    @Test fun server_unchanged_local_wins() {
        val base = contact(fullName = "Alice")
        val server = contact(fullName = "Alice")
        val local = contact(fullName = "Alice Smith")

        val result = ThreeWayMerger.merge(
            ThreeWayMerger.MergeInput(base, server, local)
        )
        assertTrue(result is ThreeWayMerger.MergeResult.AutoMerged)
        assertEquals("Alice Smith", (result as ThreeWayMerger.MergeResult.AutoMerged).merged.fullName)
    }

    @Test fun local_unchanged_server_wins() {
        val base = contact(fullName = "Alice")
        val server = contact(fullName = "Alice Johnson")
        val local = contact(fullName = "Alice")

        val result = ThreeWayMerger.merge(
            ThreeWayMerger.MergeInput(base, server, local)
        )
        assertTrue(result is ThreeWayMerger.MergeResult.AutoMerged)
        assertEquals("Alice Johnson", (result as ThreeWayMerger.MergeResult.AutoMerged).merged.fullName)
    }

    @Test fun same_field_same_value_no_conflict() {
        val base = contact(fullName = "Alice")
        val server = contact(fullName = "Alice Smith")
        val local = contact(fullName = "Alice Smith")

        val result = ThreeWayMerger.merge(
            ThreeWayMerger.MergeInput(base, server, local)
        )
        assertTrue(result is ThreeWayMerger.MergeResult.AutoMerged)
        assertEquals("Alice Smith", (result as ThreeWayMerger.MergeResult.AutoMerged).merged.fullName)
    }

    @Test fun same_field_different_value_produces_conflict() {
        val base = contact(fullName = "Alice")
        val server = contact(fullName = "Alice Johnson")
        val local = contact(fullName = "Alice Smith")

        val result = ThreeWayMerger.merge(
            ThreeWayMerger.MergeInput(base, server, local)
        )
        assertTrue(result is ThreeWayMerger.MergeResult.Conflicted)
        val conflicted = result as ThreeWayMerger.MergeResult.Conflicted
        assertEquals(1, conflicted.conflicts.size)
        assertEquals("fullName", conflicted.conflicts[0].fieldName)
        assertEquals("Alice Johnson", conflicted.conflicts[0].serverValue)
        assertEquals("Alice Smith", conflicted.conflicts[0].localValue)
    }

    @Test fun disjoint_email_additions_auto_merge() {
        val base = contact(emails = listOf(DecryptedEmail("alice@proton.me", isPrimary = true)))
        val server = contact(emails = listOf(
            DecryptedEmail("alice@proton.me", isPrimary = true),
            DecryptedEmail("alice@work.com")
        ))
        val local = contact(emails = listOf(
            DecryptedEmail("alice@proton.me", isPrimary = true),
            DecryptedEmail("alice@personal.org")
        ))

        val result = ThreeWayMerger.merge(
            ThreeWayMerger.MergeInput(base, server, local)
        )
        assertTrue(result is ThreeWayMerger.MergeResult.AutoMerged)
        val merged = (result as ThreeWayMerger.MergeResult.AutoMerged).merged
        assertEquals(3, merged.emails.size)
        val addresses = merged.emails.map { it.address }.toSet()
        assertTrue(addresses.contains("alice@proton.me"))
        assertTrue(addresses.contains("alice@work.com"))
        assertTrue(addresses.contains("alice@personal.org"))
    }

    @Test fun server_deletes_email_local_unchanged_accepts_deletion() {
        val base = contact(emails = listOf(
            DecryptedEmail("alice@proton.me", isPrimary = true),
            DecryptedEmail("old@proton.me")
        ))
        val server = contact(emails = listOf(DecryptedEmail("alice@proton.me", isPrimary = true)))
        val local = contact(emails = listOf(
            DecryptedEmail("alice@proton.me", isPrimary = true),
            DecryptedEmail("old@proton.me")
        ))

        val result = ThreeWayMerger.merge(
            ThreeWayMerger.MergeInput(base, server, local)
        )
        assertTrue(result is ThreeWayMerger.MergeResult.AutoMerged)
        val merged = (result as ThreeWayMerger.MergeResult.AutoMerged).merged
        assertEquals(1, merged.emails.size)
        assertEquals("alice@proton.me", merged.emails[0].address)
    }

    @Test fun local_deletes_email_server_unchanged_accepts_deletion() {
        val base = contact(emails = listOf(
            DecryptedEmail("alice@proton.me", isPrimary = true),
            DecryptedEmail("old@proton.me")
        ))
        val server = contact(emails = listOf(
            DecryptedEmail("alice@proton.me", isPrimary = true),
            DecryptedEmail("old@proton.me")
        ))
        val local = contact(emails = listOf(DecryptedEmail("alice@proton.me", isPrimary = true)))

        val result = ThreeWayMerger.merge(
            ThreeWayMerger.MergeInput(base, server, local)
        )
        assertTrue(result is ThreeWayMerger.MergeResult.AutoMerged)
        val merged = (result as ThreeWayMerger.MergeResult.AutoMerged).merged
        assertEquals(1, merged.emails.size)
    }

    @Test fun disjoint_field_changes_auto_merge() {
        val base = contact(
            fullName = "Alice",
            organization = DecryptedOrganization(company = "Acme")
        )
        val server = contact(
            fullName = "Alice Johnson",
            organization = DecryptedOrganization(company = "Acme")
        )
        val local = contact(
            fullName = "Alice",
            organization = DecryptedOrganization(company = "BigCo")
        )

        val result = ThreeWayMerger.merge(
            ThreeWayMerger.MergeInput(base, server, local)
        )
        assertTrue(result is ThreeWayMerger.MergeResult.AutoMerged)
        val merged = (result as ThreeWayMerger.MergeResult.AutoMerged).merged
        assertEquals("Alice Johnson", merged.fullName)
        assertEquals("BigCo", merged.organization!!.company)
    }

    @Test fun structured_name_conflict() {
        val base = contact(structuredName = DecryptedStructuredName(given = "Alice", family = "Smith"))
        val server = contact(structuredName = DecryptedStructuredName(given = "Alice", family = "Johnson"))
        val local = contact(structuredName = DecryptedStructuredName(given = "Alice", family = "Williams"))

        val result = ThreeWayMerger.merge(
            ThreeWayMerger.MergeInput(base, server, local)
        )
        assertTrue(result is ThreeWayMerger.MergeResult.Conflicted)
        val conflicted = result as ThreeWayMerger.MergeResult.Conflicted
        assertTrue(conflicted.conflicts.any { it.fieldName == "structuredName" })
    }

    @Test fun phone_addition_on_server_auto_merges() {
        val base = contact(phones = emptyList())
        val server = contact(phones = listOf(DecryptedPhone("555-1234", listOf("home"))))
        val local = contact(phones = emptyList())

        val result = ThreeWayMerger.merge(
            ThreeWayMerger.MergeInput(base, server, local)
        )
        assertTrue(result is ThreeWayMerger.MergeResult.AutoMerged)
        val merged = (result as ThreeWayMerger.MergeResult.AutoMerged).merged
        assertEquals(1, merged.phones.size)
        assertEquals("555-1234", merged.phones[0].number)
    }

    @Test fun notes_server_changed_local_unchanged_server_wins() {
        val base = contact(notes = listOf("Old note"))
        val server = contact(notes = listOf("Updated note"))
        val local = contact(notes = listOf("Old note"))

        val result = ThreeWayMerger.merge(
            ThreeWayMerger.MergeInput(base, server, local)
        )
        assertTrue(result is ThreeWayMerger.MergeResult.AutoMerged)
        val merged = (result as ThreeWayMerger.MergeResult.AutoMerged).merged
        assertEquals(listOf("Updated note"), merged.notes)
    }

    @Test fun multiple_conflicts_all_reported() {
        val base = contact(
            fullName = "Alice",
            organization = DecryptedOrganization(company = "Acme")
        )
        val server = contact(
            fullName = "Alice A",
            organization = DecryptedOrganization(company = "ServerCo")
        )
        val local = contact(
            fullName = "Alice B",
            organization = DecryptedOrganization(company = "LocalCo")
        )

        val result = ThreeWayMerger.merge(
            ThreeWayMerger.MergeInput(base, server, local)
        )
        assertTrue(result is ThreeWayMerger.MergeResult.Conflicted)
        val conflicted = result as ThreeWayMerger.MergeResult.Conflicted
        assertEquals(2, conflicted.conflicts.size)
        val fields = conflicted.conflicts.map { it.fieldName }.toSet()
        assertTrue(fields.contains("fullName"))
        assertTrue(fields.contains("organization"))
    }

    @Test fun conflicted_result_uses_local_as_default() {
        val base = contact(fullName = "Alice")
        val server = contact(fullName = "Alice A")
        val local = contact(fullName = "Alice B")

        val result = ThreeWayMerger.merge(
            ThreeWayMerger.MergeInput(base, server, local)
        )
        val conflicted = result as ThreeWayMerger.MergeResult.Conflicted
        assertEquals("Alice B", conflicted.partial.fullName)
    }

    // ---- birthday, anniversary, nicknames, websites (ADR-0023, 2026-09-24) ----

    @Test fun a_birthday_added_on_the_phone_merges_into_an_unchanged_server() {
        val base = contact()
        val local = base.copy(birthday = "1990-03-12", nicknames = listOf("Ali"))

        val result = mergePhotos(base, base, local)

        val merged = (result as ThreeWayMerger.MergeResult.AutoMerged).merged
        assertEquals("1990-03-12", merged.birthday)
        assertEquals(listOf("Ali"), merged.nicknames)
    }

    @Test fun a_birthday_changed_differently_on_both_sides_is_a_conflict() {
        val base = contact().copy(birthday = "1990-03-12")
        val server = base.copy(birthday = "1990-03-13")
        val local = base.copy(birthday = "1991-03-12")

        val result = mergePhotos(base, server, local)

        val conflicts = (result as ThreeWayMerger.MergeResult.Conflicted).conflicts
        assertEquals(listOf("birthday"), conflicts.map { it.fieldName })
    }

    @Test fun websites_added_on_each_side_are_both_kept() {
        val base = contact()
        val server = base.copy(websites = listOf("https://a.example"))
        val local = base.copy(websites = listOf("https://b.example"))

        val merged = (mergePhotos(base, server, local) as ThreeWayMerger.MergeResult.AutoMerged).merged

        assertEquals(setOf("https://a.example", "https://b.example"), merged.websites.toSet())
    }
}
