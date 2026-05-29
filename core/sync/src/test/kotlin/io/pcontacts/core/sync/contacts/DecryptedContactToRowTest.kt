// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.sync.contacts

import io.pcontacts.core.protoncontacts.DecryptedContact
import io.pcontacts.core.protoncontacts.DecryptedEmail
import io.pcontacts.core.protoncontacts.DecryptedPhone
import io.pcontacts.core.protoncontacts.DecryptedStructuredName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Codifies the displayName-fallback contract: only real Proton names
 * (FN or projected from N) survive; phone / email / IM handles never
 * synthesize a displayName because that string would land in
 * StructuredName.DISPLAY_NAME and steal the name from any local
 * RawContact Android aggregates ours with.
 */
class DecryptedContactToRowTest {

    @Test fun fullName_passes_through_when_present() {
        val row = DecryptedContactToRow.convert(
            decrypted(
                protonContactId = "p1",
                fullName = "Alice Doe",
                emails = listOf(DecryptedEmail(address = "alice@x.com", isPrimary = true))
            )
        )
        assertNotNull(row)
        assertEquals("Alice Doe", row!!.displayName)
    }

    @Test fun structured_name_only_yields_null_displayName_but_keeps_pieces() {
        val row = DecryptedContactToRow.convert(
            decrypted(
                protonContactId = "p2",
                fullName = null,
                structuredName = DecryptedStructuredName(given = "Alice", family = "Doe"),
                emails = listOf(DecryptedEmail(address = "alice@x.com", isPrimary = true))
            )
        )
        assertNotNull(row)
        // No FN → no DISPLAY_NAME fabrication. ContactsContractOps still
        // emits a StructuredName row because the structured pieces are
        // present and Android composes DISPLAY_NAME from given+family.
        assertNull(row!!.displayName)
        assertEquals("Alice", row.structuredName?.given)
        assertEquals("Doe", row.structuredName?.family)
    }

    @Test fun phone_only_without_name_yields_null_displayName() {
        val row = DecryptedContactToRow.convert(
            decrypted(
                protonContactId = "p3",
                fullName = null,
                structuredName = null,
                phones = listOf(DecryptedPhone(number = "+39 333 0000000"))
            )
        )
        assertNotNull(row)
        // The earlier behavior synthesized "+39 333 0000000" as the
        // displayName, which Android's aggregator then adopted over the
        // real name of a local SIM / WhatsApp RawContact sharing the
        // same phone. With null displayName + null structuredName the
        // writer omits the StructuredName row entirely.
        assertNull(row!!.displayName)
        assertNull(row.structuredName)
        assertEquals(1, row.phones.size)
        assertEquals("+39 333 0000000", row.phones[0].number)
    }

    @Test fun email_only_without_name_yields_null_displayName() {
        val row = DecryptedContactToRow.convert(
            decrypted(
                protonContactId = "p4",
                fullName = null,
                emails = listOf(DecryptedEmail(address = "anon@x.com", isPrimary = true))
            )
        )
        assertNotNull(row)
        assertNull(row!!.displayName)
        assertEquals(1, row.emails.size)
    }

    private fun decrypted(
        protonContactId: String,
        fullName: String? = null,
        structuredName: DecryptedStructuredName? = null,
        emails: List<DecryptedEmail> = emptyList(),
        phones: List<DecryptedPhone> = emptyList()
    ): DecryptedContact = DecryptedContact(
        protonContactId = protonContactId,
        protonUid = null,
        fullName = fullName,
        structuredName = structuredName,
        emails = emails,
        phones = phones,
        verified = true,
        cardCount = 1,
        unverifiedCardCount = 0
    )
}
