// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.sync.contacts

import io.pcontacts.core.contactswriter.ContactRow
import io.pcontacts.core.contactswriter.EmailType
import io.pcontacts.core.protoncontacts.DecryptedContact
import io.pcontacts.core.protoncontacts.DecryptedEmail
import org.junit.Assert.assertEquals
import org.junit.Test

class ExtraFieldsGuardTest {

    private val phone = ContactRow(sourceId = "c1", displayName = "Bolt", emails = emptyList())
    private val proton = DecryptedContact.empty("c1").copy(fullName = "Bolt")

    @Test fun nothing_only_on_the_phone_rewrites_as_usual() {
        assertEquals(ExtraFieldsGuard.Verdict.Rewrite, ExtraFieldsGuard.judge(phone, proton))
        assertEquals(
            ExtraFieldsGuard.Verdict.Rewrite,
            ExtraFieldsGuard.judge(phone, proton.copy(birthday = "1990-03-12", websites = listOf("https://b.example")))
        )
    }

    @Test fun a_birthday_or_nickname_only_on_the_phone_is_pushed_first() {
        assertEquals(
            ExtraFieldsGuard.Verdict.PushFirst,
            ExtraFieldsGuard.judge(phone.copy(birthday = "1990-03-12"), proton)
        )
        assertEquals(
            ExtraFieldsGuard.Verdict.PushFirst,
            ExtraFieldsGuard.judge(phone.copy(nicknames = listOf("B")), proton.copy(nicknames = listOf("A")))
        )
    }

    @Test fun a_different_birthday_on_each_side_is_a_clash() {
        assertEquals(
            ExtraFieldsGuard.Verdict.Clash(listOf("birthday")),
            ExtraFieldsGuard.judge(phone.copy(birthday = "1990-03-12"), proton.copy(birthday = "1990-03-13"))
        )
    }

    // ---- the roll to v4: an email's home/work type (ADR-0023, 2026-10-15) ----

    private val typedPhone = phone.copy(
        emails = listOf("b@example.com"),
        emailTypes = mapOf("b@example.com" to EmailType.WORK)
    )

    @Test fun an_email_type_only_on_the_phone_is_pushed_first() {
        val untyped = proton.copy(emails = listOf(DecryptedEmail("b@example.com", types = listOf("other"))))
        assertEquals(ExtraFieldsGuard.Verdict.PushFirst, ExtraFieldsGuard.judge(typedPhone, untyped))
    }

    @Test fun the_same_email_type_on_both_sides_rewrites_as_usual() {
        val work = proton.copy(emails = listOf(DecryptedEmail("b@example.com", types = listOf("WORK"))))
        assertEquals(ExtraFieldsGuard.Verdict.Rewrite, ExtraFieldsGuard.judge(typedPhone, work))
    }

    @Test fun a_type_only_on_proton_rewrites_and_brings_it_to_the_phone() {
        val home = proton.copy(emails = listOf(DecryptedEmail("b@example.com", types = listOf("home"))))
        val untypedPhone = phone.copy(emails = listOf("b@example.com"))
        assertEquals(ExtraFieldsGuard.Verdict.Rewrite, ExtraFieldsGuard.judge(untypedPhone, home))
    }

    @Test fun a_different_email_type_on_each_side_is_a_clash() {
        val home = proton.copy(emails = listOf(DecryptedEmail("b@example.com", types = listOf("home"))))
        assertEquals(ExtraFieldsGuard.Verdict.Clash(listOf("email type")), ExtraFieldsGuard.judge(typedPhone, home))
    }
}
