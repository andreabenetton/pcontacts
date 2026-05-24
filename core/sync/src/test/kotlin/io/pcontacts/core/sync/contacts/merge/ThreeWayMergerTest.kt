// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.sync.contacts.merge

import io.pcontacts.core.protoncontacts.DecryptedContact
import io.pcontacts.core.protoncontacts.DecryptedEmail
import io.pcontacts.core.protoncontacts.DecryptedOrganization
import io.pcontacts.core.protoncontacts.DecryptedPhone
import io.pcontacts.core.protoncontacts.DecryptedStructuredName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreeWayMergerTest {

    private fun contact(
        fullName: String? = "Alice",
        emails: List<DecryptedEmail> = listOf(DecryptedEmail("alice@proton.me", isPrimary = true)),
        phones: List<DecryptedPhone> = emptyList(),
        structuredName: DecryptedStructuredName? = null,
        organization: DecryptedOrganization? = null,
        notes: List<String> = emptyList()
    ) = DecryptedContact(
        protonContactId = "ct-1",
        protonUid = "uid-1",
        fullName = fullName,
        structuredName = structuredName,
        emails = emails,
        phones = phones,
        organization = organization,
        notes = notes,
        verified = true,
        cardCount = 2,
        unverifiedCardCount = 0
    )

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
}
