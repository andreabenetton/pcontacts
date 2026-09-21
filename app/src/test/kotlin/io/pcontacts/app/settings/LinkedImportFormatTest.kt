// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.app.settings

import io.pcontacts.core.contactswriter.ImAccount
import io.pcontacts.core.contactswriter.ImProtocol
import io.pcontacts.core.contactswriter.LinkedField
import io.pcontacts.core.contactswriter.Organization
import io.pcontacts.core.contactswriter.PhoneEntry
import io.pcontacts.core.contactswriter.PostalAddress
import io.pcontacts.feature.settings.LinkedFieldKind
import org.junit.Assert.assertEquals
import org.junit.Test

class LinkedImportFormatTest {

    @Test fun every_field_maps_to_its_kind_and_a_readable_value() {
        val cases = listOf(
            LinkedField.PhoneNumber(PhoneEntry("+39 333 1234567")) to (LinkedFieldKind.PHONE to "+39 333 1234567"),
            LinkedField.EmailAddress("a@b") to (LinkedFieldKind.EMAIL to "a@b"),
            LinkedField.Address(PostalAddress(street = "Via Roma 1", city = "Milano", country = "IT")) to
                (LinkedFieldKind.ADDRESS to "Via Roma 1, Milano, IT"),
            LinkedField.Org(Organization(company = "ACME", title = "CTO")) to
                (LinkedFieldKind.ORGANIZATION to "ACME · CTO"),
            LinkedField.NoteText("met in Milan") to (LinkedFieldKind.NOTE to "met in Milan"),
            LinkedField.Im(ImAccount("handle", ImProtocol.SKYPE)) to (LinkedFieldKind.IM to "skype: handle"),
            LinkedField.Im(ImAccount("h", ImProtocol.CUSTOM, customProtocol = "Signal")) to
                (LinkedFieldKind.IM to "Signal: h")
        )
        for ((field, expected) in cases) {
            assertEquals(expected.first, LinkedImportFormat.kind(field))
            assertEquals(expected.second, LinkedImportFormat.value(field))
        }
    }
}
