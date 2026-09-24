// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.contactswriter

import android.accounts.Account
import io.pcontacts.core.contactswriter.LinkedContactsScanner.Member
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class LinkedContactsScannerTest {

    private val account = Account("alice@proton.me", "io.pcontacts.account")

    private fun member(
        rawId: Long,
        contactId: Long,
        type: String?,
        name: String? = null,
        synced: Boolean = false
    ) = Member(
        rawContactId = rawId,
        contactId = contactId,
        accountType = type,
        accountName = if (type == account.type) account.name else "x",
        displayName = name,
        hasSourceId = synced
    )

    private fun row(vararg phones: String, email: String? = null, note: String? = null) = ContactRow(
        sourceId = "",
        displayName = null,
        emails = listOfNotNull(email),
        phones = phones.map { PhoneEntry(it) },
        notes = listOfNotNull(note)
    )

    @Test fun lists_contacts_missing_from_proton_and_proton_copies_lacking_fields_sorted_by_name() {
        val aggregates = mapOf(
            // WhatsApp + Signal (+ a data-less Telegram row) only, with a phone: creatable.
            1L to listOf(
                member(10, 1, "com.whatsapp", "Zoe"),
                member(11, 1, "org.signal", "Zoe"),
                member(12, 1, "org.telegram", "Zoe")
            ),
            // Proton copy already has everything: skipped.
            2L to listOf(member(20, 2, account.type, "Bob", synced = true), member(21, 2, "com.whatsapp", "Bob")),
            // Proton copy lacks the local phone.
            3L to listOf(member(30, 3, account.type, "Amy", synced = true), member(31, 3, null, "Amy")),
            // Telegram only, action rows only (no ContactRow): skipped.
            4L to listOf(member(40, 4, "org.telegram", "Tom"))
        )
        val rows = mapOf(
            10L to row("+39 333 0000001"),
            11L to row("333 0000001"),
            20L to row("+39 333 0000002"),
            21L to row("+39 333 0000002"),
            30L to row(email = "amy@example.org"),
            31L to row("+39 333 0000003", email = "AMY@example.org")
        )
        assertEquals(
            listOf(
                LinkedContactSummary(
                    contactId = 3L,
                    displayName = "Amy",
                    sourceAccountTypes = listOf(null),
                    hasProtonCopy = true,
                    newFieldCount = 1
                ),
                LinkedContactSummary(
                    contactId = 1L,
                    displayName = "Zoe",
                    sourceAccountTypes = listOf("com.whatsapp", "org.signal", "org.telegram"),
                    hasProtonCopy = false,
                    newFieldCount = 1
                )
            ),
            LinkedContactsScanner.summarize(account, aggregates, rows)
        )
    }

    @Test fun a_contact_outside_proton_needs_a_reachable_field_to_be_listed() {
        val whatsappOnly = mapOf(5L to listOf(member(50, 5, "com.whatsapp", "Nia")))
        // A digit-less "phone" is never offered, leaving only the note: nothing to create from.
        val junk = row("n/a", note = "met in Milan")
        assertTrue(LinkedContactsScanner.summarize(account, whatsappOnly, mapOf(50L to junk)).isEmpty())

        val real = row("+39 333 0000005", note = "met in Milan")
        val listed = LinkedContactsScanner.summarize(account, whatsappOnly, mapOf(50L to real)).single()
        assertEquals(2, listed.newFieldCount)
    }

    private fun orphan(rawId: Long, contactId: Long, name: String) = Member(
        rawContactId = rawId,
        contactId = contactId,
        accountType = "PHONE",
        accountName = "PHONE",
        displayName = name,
        hasSourceId = false
    )

    @Test fun an_orphan_phone_contact_without_a_proton_copy_is_movable_and_flagged_when_lossy() {
        val aggregates = mapOf(
            1L to listOf(orphan(10, 1, "Ann")),
            2L to listOf(orphan(20, 2, "Ben")),
            3L to listOf(member(30, 3, account.type, "Cid", synced = true), orphan(31, 3, "Cid")),
            4L to listOf(member(40, 4, "vnd.sec.contact.phone", "Dee"))
        )
        val rows = mapOf(
            10L to row("+39 333 0000010"),
            20L to row("+39 333 0000020"),
            30L to row(email = "cid@x"),
            31L to row("+39 333 0000031"),
            40L to row("+39 333 0000040")
        )

        val byName = LinkedContactsScanner.summarize(account, aggregates, rows, lossyOrphans = setOf(20L))
            .associateBy { it.displayName }

        assertTrue(byName.getValue("Ann").movable)
        assertFalse(byName.getValue("Ann").moveLoses)
        assertTrue(byName.getValue("Ben").moveLoses)
        assertFalse("a Proton copy exists", byName.getValue("Cid").movable)
        assertFalse("not the exact orphan account", byName.getValue("Dee").movable)
    }

    @Test fun allowlist_is_exactly_the_adr_0023_list() {
        assertEquals(
            listOf(
                "vnd.android.cursor.item/name",
                "vnd.android.cursor.item/email_v2",
                "vnd.android.cursor.item/phone_v2",
                "vnd.android.cursor.item/postal-address_v2",
                "vnd.android.cursor.item/organization",
                "vnd.android.cursor.item/note",
                "vnd.android.cursor.item/im",
                "vnd.android.cursor.item/contact_event",
                "vnd.android.cursor.item/nickname",
                "vnd.android.cursor.item/website"
            ),
            LinkedContactsScanner.ALLOWED_MIMETYPES
        )
    }
}
