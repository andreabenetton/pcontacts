// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.contactswriter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkedContactDiffTest {

    private val proton = ContactRow(
        sourceId = "p1",
        displayName = "Evelino",
        emails = listOf("Evelino@Example.org"),
        phones = listOf(PhoneEntry("+39 333 1234567")),
        notes = listOf("known")
    )

    private fun sibling(
        emails: List<String> = emptyList(),
        phones: List<PhoneEntry> = emptyList(),
        organization: Organization? = null
    ) = ContactRow(
        sourceId = "",
        displayName = null,
        emails = emails,
        phones = phones,
        organization = organization
    )

    @Test fun offers_only_fields_missing_from_the_proton_copy() {
        val work = PhoneEntry("+39 02 9876543", PhoneType.WORK)
        val whatsapp = sibling(
            emails = listOf("evelino@example.org"),
            phones = listOf(PhoneEntry("+39 333 1234567"), work)
        )
        val out = LinkedContactDiff.candidates(proton, listOf("com.whatsapp" to whatsapp))
        assertEquals(listOf(LinkedFieldCandidate(LinkedField.PhoneNumber(work), listOf("com.whatsapp"))), out)
    }

    @Test fun phone_suffix_and_email_case_count_as_already_present() {
        val local = sibling(
            emails = listOf("EVELINO@example.org "),
            phones = listOf(PhoneEntry("333 1234567"), PhoneEntry("0039 333 1234567"))
        )
        assertTrue(LinkedContactDiff.candidates(proton, listOf(null to local)).isEmpty())
    }

    @Test fun offered_phones_and_addresses_drop_the_sibling_primary_flag() {
        val local = sibling(phones = listOf(PhoneEntry("+39 02 9876543", isPrimary = true)))
            .copy(addresses = listOf(PostalAddress(city = "Milano", isPrimary = true)))
        val out = LinkedContactDiff.candidates(proton, listOf(null to local)).map { it.field }
        assertEquals(
            listOf(
                LinkedField.PhoneNumber(PhoneEntry("+39 02 9876543")),
                LinkedField.Address(PostalAddress(city = "Milano"))
            ),
            out
        )
    }

    @Test fun short_numbers_are_not_suffix_matched() {
        val local = sibling(phones = listOf(PhoneEntry("34567")))
        assertEquals(1, LinkedContactDiff.candidates(proton, listOf(null to local)).size)
    }

    @Test fun same_value_on_several_siblings_is_offered_once_attributed_to_each_source() {
        val phone = PhoneEntry("+39 02 9876543")
        val a = sibling(phones = listOf(phone))
        val b = sibling(phones = listOf(PhoneEntry("02 9876543")), emails = listOf("x@y"))
        val c = sibling(phones = listOf(PhoneEntry("+39029876543")), emails = listOf("X@Y"))
        val out = LinkedContactDiff.candidates(proton, listOf("com.whatsapp" to a, null to b, null to c))
        assertEquals(
            listOf(
                LinkedFieldCandidate(LinkedField.PhoneNumber(phone), listOf("com.whatsapp", null)),
                LinkedFieldCandidate(LinkedField.EmailAddress("x@y"), listOf(null))
            ),
            out
        )
    }

    @Test fun organization_offered_only_when_proton_has_none() {
        val org = Organization(company = "ACME")
        val row = sibling(emails = listOf("x@y"), organization = org)
        val withOrg = proton.copy(organization = Organization(title = "CTO"))

        val offered = LinkedContactDiff.candidates(proton, listOf(null to row))
        assertTrue(offered.any { it.field == LinkedField.Org(org) })

        val notOffered = LinkedContactDiff.candidates(withOrg, listOf(null to row))
        assertTrue(notOffered.none { it.field is LinkedField.Org })
    }

    @Test fun null_proton_row_offers_everything() {
        val row = ContactRow(
            sourceId = "",
            displayName = null,
            emails = listOf("a@b"),
            addresses = listOf(PostalAddress(city = "Milano")),
            notes = listOf("note", "  "),
            imAccounts = listOf(ImAccount("handle", ImProtocol.SKYPE))
        )
        val out = LinkedContactDiff.candidates(null, listOf(null to row)).map { it.field }
        assertEquals(
            listOf(
                LinkedField.EmailAddress("a@b"),
                LinkedField.Address(PostalAddress(city = "Milano")),
                LinkedField.NoteText("note"),
                LinkedField.Im(ImAccount("handle", ImProtocol.SKYPE))
            ),
            out
        )
    }
}
