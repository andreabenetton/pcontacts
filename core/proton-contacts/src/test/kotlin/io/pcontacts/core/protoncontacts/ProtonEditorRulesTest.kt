// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.protoncontacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtonEditorRulesTest {

    private fun contact(
        fullName: String? = "Alice",
        given: String? = null,
        family: String? = null,
        emails: List<String> = emptyList()
    ) = DecryptedContact(
        protonContactId = "c",
        protonUid = "u",
        fullName = fullName,
        structuredName = if (given == null && family == null) null else DecryptedStructuredName(given, family),
        emails = emails.map { DecryptedEmail(it) },
        verified = true,
        cardCount = 2,
        unverifiedCardCount = 0
    )

    @Test fun a_contact_within_the_rules_is_sent_as_it_is() {
        val alice = contact(given = "Alice", family = "Smith", emails = listOf("alice@example.com"))

        val conformed = ProtonEditorRules.conform(alice)

        assertEquals(alice, conformed.contact)
        assertTrue(conformed.adjustments.isEmpty())
    }

    @Test fun a_name_over_190_is_cut_to_190() {
        val conformed = ProtonEditorRules.conform(contact(fullName = "n".repeat(191)))

        assertEquals("n".repeat(190), conformed.contact.fullName)
        assertEquals(setOf(ProtonAdjustment.NAME_SHORTENED), conformed.adjustments)
    }

    @Test fun a_name_of_190_after_trimming_is_left_alone() {
        val name = " " + "n".repeat(190) + " "

        val conformed = ProtonEditorRules.conform(contact(fullName = name))

        assertEquals(name, conformed.contact.fullName)
        assertTrue(conformed.adjustments.isEmpty())
    }

    @Test fun a_first_or_last_name_over_94_is_cut_to_94() {
        val conformed = ProtonEditorRules.conform(contact(given = "g".repeat(95), family = "f".repeat(400)))

        assertEquals("g".repeat(94), conformed.contact.structuredName?.given)
        assertEquals("f".repeat(94), conformed.contact.structuredName?.family)
        assertEquals(
            setOf(ProtonAdjustment.FIRST_NAME_SHORTENED, ProtonAdjustment.LAST_NAME_SHORTENED),
            conformed.adjustments
        )
    }

    @Test fun a_cut_never_splits_a_surrogate_pair_or_leaves_a_trailing_space() {
        val emoji = "😀"
        val conformed = ProtonEditorRules.conform(contact(fullName = "n".repeat(189) + emoji + "tail"))
        assertEquals("n".repeat(189), conformed.contact.fullName)

        val spaced = ProtonEditorRules.conform(contact(fullName = "n".repeat(189) + " tail"))
        assertEquals("n".repeat(189), spaced.contact.fullName)
    }

    @Test fun spaces_around_an_email_are_removed() {
        val conformed = ProtonEditorRules.conform(contact(emails = listOf(" alice@example.com ")))

        assertEquals(listOf("alice@example.com"), conformed.contact.emails.map { it.address })
        assertEquals(setOf(ProtonAdjustment.EMAIL_TRIMMED), conformed.adjustments)
    }

    @Test fun an_invalid_email_is_sent_as_it_is_for_proton_to_decide() {
        val conformed = ProtonEditorRules.conform(contact(emails = listOf("not-an-email")))

        assertEquals(listOf("not-an-email"), conformed.contact.emails.map { it.address })
        assertTrue(conformed.adjustments.isEmpty())
    }

    @Test fun a_value_proton_already_holds_is_never_rewritten() {
        val long = contact(fullName = "n".repeat(300), emails = listOf(" kept@example.com"))

        val conformed = ProtonEditorRules.conform(long, onProton = long)

        assertEquals(long, conformed.contact)
        assertTrue(conformed.adjustments.isEmpty())
    }

    @Test fun an_adjustment_proton_already_holds_is_made_but_not_reported_again() {
        val onProton = contact(fullName = "n".repeat(190), emails = listOf("alice@example.com"))
        val phone = contact(fullName = "n".repeat(250), emails = listOf("alice@example.com "))

        val conformed = ProtonEditorRules.conform(phone, onProton)

        assertEquals(onProton, conformed.contact)
        assertTrue(conformed.adjustments.isEmpty())
    }

    @Test fun every_kind_round_trips_its_stored_key() {
        ProtonAdjustment.entries.forEach { assertEquals(it, ProtonAdjustment.fromKey(it.key)) }
    }
}
