// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.sync.contacts

import io.pcontacts.core.contactswriter.ContactRow
import io.pcontacts.core.protoncontacts.DecryptedContact
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
}
