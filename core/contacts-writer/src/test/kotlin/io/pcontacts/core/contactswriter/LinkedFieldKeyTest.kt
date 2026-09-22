// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.contactswriter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class LinkedFieldKeyTest {

    @Test fun keys_normalise_spacing_and_case_but_keep_kind_and_value_apart() {
        assertEquals(
            LinkedField.PhoneNumber(PhoneEntry("+39 333 000")).key,
            LinkedField.PhoneNumber(PhoneEntry("+39333-000", PhoneType.HOME, isPrimary = true)).key
        )
        assertEquals(
            LinkedField.EmailAddress("Alice@Example.com ").key,
            LinkedField.EmailAddress("alice@example.com").key
        )
        assertNotEquals(LinkedField.NoteText("a").key, LinkedField.EmailAddress("a").key)
        assertNotEquals(
            LinkedField.Address(PostalAddress(street = "1 Main", country = "IT")).key,
            LinkedField.Address(PostalAddress(street = "1 Main", country = "SM")).key
        )
    }

    @Test fun im_key_uses_the_custom_protocol_when_present() {
        val custom = LinkedField.Im(ImAccount("h", ImProtocol.CUSTOM, customProtocol = "matrix"))
        val builtIn = LinkedField.Im(ImAccount("h", ImProtocol.JABBER))
        assertEquals("im:matrix:h", custom.key)
        assertEquals("im:JABBER:h", builtIn.key)
    }
}
