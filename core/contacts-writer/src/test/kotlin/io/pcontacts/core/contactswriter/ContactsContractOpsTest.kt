// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.contactswriter

import android.accounts.Account
import android.provider.ContactsContract
import android.provider.ContactsContract.RawContacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Structural tests for ContactsContractOps under Robolectric. The
 * actual ContentValues (e.g. DISPLAY_NAME = "Alice") aren't easily
 * introspectable via the public ContentProviderOperation API; full
 * value-shape verification ships in an instrumented test once an
 * emulator pipeline is set up. These tests cover URI shapes,
 * per-intent op counts, and operation kinds — which is what we'd
 * silently break by refactoring.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ContactsContractOpsTest {

    private val account = Account("alice@proton.me", "io.pcontacts.account")

    @Test fun create_emits_one_RawContacts_insert_plus_StructuredName_plus_Email_plus_chip() {
        val ops = ContactsContractOps.build(
            account = account,
            intent = RawContactOpIntent.CreateContact(
                ContactRow(sourceId = "c1", displayName = "Alice", emails = listOf("alice@proton.me"))
            ),
            baseIdx = 0
        )
        // 1 RawContacts + 1 StructuredName + 1 Email + 1 Send-via-Proton-Mail chip = 4.
        assertEquals(4, ops.size)
        assertTrue("all ops in a Create batch must be inserts", ops.all { it.isInsert })

        // RawContacts URI carries caller_is_syncadapter and account params.
        val raw0Uri = ops[0].uri
        assertEquals("true", raw0Uri.getQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER))
        assertEquals("alice@proton.me", raw0Uri.getQueryParameter(RawContacts.ACCOUNT_NAME))

        // Every child Data insert (including the chip) carries
        // caller_is_syncadapter to prevent Android from marking the
        // parent RawContact as DIRTY (ADR-0010).
        ops.drop(1).forEach { op ->
            assertEquals("true", op.uri.getQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER))
        }
    }

    @Test fun create_with_no_name_omits_StructuredName_row() {
        // Phone-only Proton contact with no FN and no N. Writing a
        // synthetic DISPLAY_NAME = "+39 …" would let Android's aggregator
        // overwrite a local RawContact's real name (e.g. a WhatsApp /
        // SIM entry sharing the same phone) — see ContactRow KDoc.
        val ops = ContactsContractOps.build(
            account = account,
            intent = RawContactOpIntent.CreateContact(
                ContactRow(
                    sourceId = "c-nameless",
                    displayName = null,
                    structuredName = null,
                    emails = emptyList(),
                    phones = listOf(PhoneEntry(number = "+39 333 0000000"))
                )
            ),
            baseIdx = 0
        )
        // 1 RawContacts + 1 Phone — no StructuredName.
        assertEquals(2, ops.size)
        assertTrue("op 0 must be RawContacts insert", ops[0].isInsert)
        assertTrue("op 1 must be Phone insert", ops[1].isInsert)
    }

    @Test fun update_with_no_name_omits_StructuredName_row() {
        val ops = ContactsContractOps.build(
            account = account,
            intent = RawContactOpIntent.UpdateContact(
                rawContactId = 42L,
                row = ContactRow(
                    sourceId = "c-nameless",
                    displayName = null,
                    structuredName = null,
                    emails = emptyList(),
                    phones = listOf(PhoneEntry(number = "+39 333 0000000"))
                )
            )
        )
        // 1 Delete (wipe child rows) + 1 Phone — no StructuredName re-insert.
        assertEquals(2, ops.size)
        assertTrue("op 0 must be delete", ops[0].isDelete)
        assertTrue("op 1 must be Phone insert", ops[1].isInsert)
    }

    @Test fun update_emits_delete_data_then_StructuredName_plus_Email_plus_chip_reinserts() {
        val ops = ContactsContractOps.build(
            account = account,
            intent = RawContactOpIntent.UpdateContact(
                rawContactId = 100L,
                row = ContactRow(sourceId = "c1", displayName = "Alice", emails = listOf("alice@proton.me"))
            )
        )
        // 1 Delete + 1 StructuredName + 1 Email + 1 Send-via-Proton-Mail chip = 4.
        assertEquals(4, ops.size)
        assertTrue("op 0 must be delete", ops[0].isDelete)
        assertTrue("ops 1..n must be inserts", ops.drop(1).all { it.isInsert })

        // Delete URI must carry CALLER_IS_SYNCADAPTER to avoid tombstone resurrection.
        assertEquals("true", ops[0].uri.getQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER))
    }

    @Test fun delete_emits_single_RawContacts_delete_with_selection_on_source_id() {
        val ops = ContactsContractOps.build(
            account = account,
            intent = RawContactOpIntent.DeleteContact(sourceId = "c1")
        )
        assertEquals(1, ops.size)
        assertTrue("op 0 must be delete", ops[0].isDelete)
        assertNotNull(ops[0].uri)
        assertEquals("true", ops[0].uri.getQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER))
    }

    @Test fun create_with_three_emails_emits_one_RawContacts_plus_StructuredName_plus_three_Email_rows() {
        val ops = ContactsContractOps.build(
            account = account,
            intent = RawContactOpIntent.CreateContact(
                ContactRow(
                    sourceId = "c1",
                    displayName = "Alice",
                    emails = listOf(
                        "alice@proton.me",     // position 0 → primary
                        "alice.work@x.com",
                        "alice.alt@x.com"
                    )
                )
            ),
            baseIdx = 0
        )
        // 1 RawContacts + 1 StructuredName + 3 Email + 3 chip = 8 ops.
        assertEquals(8, ops.size)
        assertTrue("op 0 must be RawContacts insert", ops[0].isInsert)
        assertTrue(ops.all { it.isInsert })
    }

    @Test fun update_with_two_emails_emits_one_delete_plus_StructuredName_plus_two_Email_rows() {
        val ops = ContactsContractOps.build(
            account = account,
            intent = RawContactOpIntent.UpdateContact(
                rawContactId = 100L,
                row = ContactRow(
                    sourceId = "c1",
                    displayName = "Alice",
                    emails = listOf("primary@x.com", "alt@x.com")
                )
            )
        )
        // 1 Delete + 1 StructuredName + 2 Email + 2 chip = 6 ops.
        assertEquals(6, ops.size)
        assertTrue(ops[0].isDelete)        // wipe child Data rows
        assertTrue("ops 1..n must be inserts", ops.drop(1).all { it.isInsert })
    }

    @Test fun contact_row_rejects_empty_email_AND_empty_phone_list() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            ContactRow(
                sourceId = "c1",
                displayName = "Alice",
                emails = emptyList(),
                phones = emptyList()
            )
        }
    }

    @Test fun phone_only_contact_is_allowed_and_emits_RawContacts_StructuredName_plus_Phone_rows() {
        val ops = ContactsContractOps.build(
            account = account,
            intent = RawContactOpIntent.CreateContact(
                ContactRow(
                    sourceId = "c1",
                    displayName = "Alice",
                    emails = emptyList(),
                    phones = listOf(PhoneEntry(number = "+1 555 0100", type = PhoneType.MOBILE))
                )
            ),
            baseIdx = 0
        )
        // 1 RawContacts + 1 StructuredName + 1 Phone = 3 ops (no emails).
        assertEquals(3, ops.size)
        assertTrue(ops.all { it.isInsert })
    }

    @Test fun create_with_structured_name_pieces_and_two_phones_emits_full_data_row_set() {
        val ops = ContactsContractOps.build(
            account = account,
            intent = RawContactOpIntent.CreateContact(
                ContactRow(
                    sourceId = "c1",
                    displayName = "Dr. Alice Marie Doe PhD",
                    structuredName = StructuredName(
                        given = "Alice",
                        family = "Doe",
                        middle = "Marie",
                        prefix = "Dr",
                        suffix = "PhD"
                    ),
                    emails = listOf("alice@proton.me"),
                    phones = listOf(
                        PhoneEntry(number = "+1 555 0100", type = PhoneType.HOME),
                        PhoneEntry(number = "+1 555 0101", type = PhoneType.MOBILE, isPrimary = true)
                    )
                )
            ),
            baseIdx = 0
        )
        // 1 RawContacts + 1 StructuredName + 1 Email + 1 chip + 2 Phone = 6 ops.
        assertEquals(6, ops.size)
        assertTrue(ops.all { it.isInsert })
    }

    @Test fun update_with_phones_emits_one_delete_plus_StructuredName_plus_Email_plus_two_Phone_rows() {
        val ops = ContactsContractOps.build(
            account = account,
            intent = RawContactOpIntent.UpdateContact(
                rawContactId = 100L,
                row = ContactRow(
                    sourceId = "c1",
                    displayName = "Alice",
                    emails = listOf("alice@proton.me"),
                    phones = listOf(
                        PhoneEntry(number = "+1 555 0100", type = PhoneType.HOME),
                        PhoneEntry(number = "+1 555 0101", type = PhoneType.MOBILE)
                    )
                )
            )
        )
        // 1 Delete + 1 StructuredName + 1 Email + 1 chip + 2 Phone = 6 ops.
        assertEquals(6, ops.size)
        assertTrue(ops[0].isDelete)
        assertTrue(ops.drop(1).all { it.isInsert })
    }

    @Test fun create_with_full_field_set_emits_one_row_per_each() {
        val ops = ContactsContractOps.build(
            account = account,
            intent = RawContactOpIntent.CreateContact(
                ContactRow(
                    sourceId = "c1",
                    displayName = "Alice Doe",
                    structuredName = StructuredName(given = "Alice", family = "Doe"),
                    emails = listOf("alice@proton.me"),
                    phones = listOf(PhoneEntry(number = "+1 555 0100", type = PhoneType.MOBILE)),
                    addresses = listOf(
                        PostalAddress(
                            street = "100 Main St",
                            city = "Springfield",
                            region = "IL",
                            postcode = "62704",
                            country = "USA",
                            type = PostalAddressType.HOME,
                            isPrimary = true
                        )
                    ),
                    organization = Organization(
                        company = "Acme Inc.",
                        department = "R&D",
                        title = "Principal Engineer"
                    ),
                    notes = listOf("First note", "Second note"),
                    imAccounts = listOf(
                        ImAccount(handle = "alice@chat", protocol = ImProtocol.JABBER),
                        ImAccount(handle = "alice.live", protocol = ImProtocol.SKYPE)
                    ),
                    photo = ContactPhoto(data = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))
                )
            ),
            baseIdx = 0
        )
        // 1 RawContacts + 1 StructuredName + 1 Email + 1 chip + 1 Phone + 1 Postal
        //   + 1 Organization + 2 Note + 2 Im + 1 Photo = 12.
        assertEquals(12, ops.size)
        assertTrue("all ops in a Create batch must be inserts", ops.all { it.isInsert })
    }

    @Test fun update_with_full_field_set_emits_one_delete_plus_each_inserted_row() {
        val ops = ContactsContractOps.build(
            account = account,
            intent = RawContactOpIntent.UpdateContact(
                rawContactId = 100L,
                row = ContactRow(
                    sourceId = "c1",
                    displayName = "Alice",
                    emails = listOf("alice@proton.me"),
                    addresses = listOf(PostalAddress(city = "Springfield")),
                    notes = listOf("note-one"),
                    imAccounts = listOf(ImAccount(handle = "alice@chat", protocol = ImProtocol.JABBER)),
                    organization = Organization(company = "Acme")
                )
            )
        )
        // 1 Delete + 1 StructuredName + 1 Email + 1 chip + 1 Postal
        //   + 1 Organization + 1 Note + 1 Im = 8.
        assertEquals(8, ops.size)
        assertTrue(ops[0].isDelete)
        assertTrue(ops.drop(1).all { it.isInsert })
    }

    @Test fun contact_row_now_accepts_address_or_im_only_contacts() {
        // Phone-only already covered; assert address-only and IM-only construct cleanly.
        ContactRow(
            sourceId = "c1",
            displayName = "Alice",
            emails = emptyList(),
            addresses = listOf(PostalAddress(city = "Springfield"))
        )
        ContactRow(
            sourceId = "c2",
            displayName = "Bob",
            emails = emptyList(),
            imAccounts = listOf(ImAccount(handle = "bob@chat", protocol = ImProtocol.JABBER))
        )
    }

    @Test fun contact_row_still_rejects_completely_actionless_rows() {
        // No email, no phone, no address, no IM — must still be rejected.
        // Note / org / photo alone aren't enough; the user can't do anything
        // with a contact carrying just those.
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            ContactRow(
                sourceId = "c1",
                displayName = "Alice",
                emails = emptyList(),
                notes = listOf("alone"),
                organization = Organization(company = "Acme")
            )
        }
    }

    @Test fun baseIdx_does_not_change_op_count_for_create() {
        // Smoke check — confirming the API accepts non-zero baseIdx without
        // throwing or adding/removing ops. Back-reference correctness lives
        // in BatchPlannerTest where the assembled batch matters.
        val ops = ContactsContractOps.build(
            account = account,
            intent = RawContactOpIntent.CreateContact(
                ContactRow(sourceId = "c1", displayName = "Alice", emails = listOf("alice@proton.me"))
            ),
            baseIdx = 449
        )
        // 1 RawContacts + 1 StructuredName + 1 Email + 1 chip.
        assertEquals(4, ops.size)
    }

    @Test fun create_emits_one_send_via_proton_mail_chip_per_email() {
        // ADR-0021: a custom MIMETYPE row per email lets Fossify-style
        // Contacts apps render a chip that routes the user to Proton
        // Mail (Android app if installed, web fallback otherwise).
        val ops = ContactsContractOps.build(
            account = account,
            intent = RawContactOpIntent.CreateContact(
                ContactRow(
                    sourceId = "c1",
                    displayName = "Alice",
                    emails = listOf("alice@proton.me", "alice.work@x.com")
                )
            ),
            baseIdx = 0
        )
        // 1 RawContacts + 1 StructuredName + 2 Email + 2 chip = 6.
        assertEquals(6, ops.size)
        assertTrue(ops.all { it.isInsert })
    }

    @Test fun chip_emission_is_skipped_when_contact_has_no_emails() {
        // Phone-only contact: no emails → no Send-via-Proton-Mail chip
        // rows. Otherwise an email-less contact would carry an
        // address-less chip Android can't route to anything useful.
        val ops = ContactsContractOps.build(
            account = account,
            intent = RawContactOpIntent.CreateContact(
                ContactRow(
                    sourceId = "c1",
                    displayName = null,
                    emails = emptyList(),
                    phones = listOf(PhoneEntry(number = "+1 555 0100"))
                )
            ),
            baseIdx = 0
        )
        // 1 RawContacts + 1 Phone = 2. No StructuredName (no name),
        // no chip (no email).
        assertEquals(2, ops.size)
    }
}
