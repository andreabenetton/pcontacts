// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.protoncontacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class DecryptedContactJsonTest {

    private val contact = DecryptedContact(
        protonContactId = "ct-1",
        protonUid = "uid-1",
        fullName = "Alice Example",
        structuredName = DecryptedStructuredName(given = "Alice", family = "Example", prefixes = listOf("Dr")),
        emails = listOf(DecryptedEmail("alice@example.com", types = listOf("work"), isPrimary = true)),
        phones = listOf(DecryptedPhone("+15551234567", types = listOf("cell"))),
        addresses = listOf(DecryptedAddress(street = "1 Main St", locality = "Springfield", country = "US")),
        organization = DecryptedOrganization(company = "ACME", title = "CTO"),
        notes = listOf("met at conf"),
        imAccounts = listOf(DecryptedIm(handle = "alice@jabber.org", protocol = "xmpp")),
        photo = DecryptedPhoto(byteArrayOf(1, 2, 3), "image/jpeg"),
        verified = true,
        cardCount = 2,
        unverifiedCardCount = 0
    )

    @Test fun round_trips_every_merge_field() {
        val decoded = DecryptedContactJson.decode(DecryptedContactJson.encode(contact))

        assertEquals(contact.copy(photo = null), decoded)
    }

    @Test fun photo_bytes_are_never_encoded() {
        val text = DecryptedContactJson.encode(contact).decodeToString()

        assertFalse(text.contains("photo"))
        assertFalse(text.contains("image/jpeg"))
        assertNull(DecryptedContactJson.decode(DecryptedContactJson.encode(contact))?.photo)
    }

    @Test fun unknown_keys_are_ignored() {
        val text = DecryptedContactJson.encode(contact).decodeToString()
            .replaceFirst("\"contact\":{", "\"contact\":{\"futureField\":\"x\",")

        assertEquals(contact.copy(photo = null), DecryptedContactJson.decode(text.encodeToByteArray()))
    }

    @Test fun garbage_decodes_to_null() {
        assertNull(DecryptedContactJson.decode("not json".encodeToByteArray()))
        assertNull(DecryptedContactJson.decode(byteArrayOf()))
        assertNull(DecryptedContactJson.decode("{\"v\":1}".encodeToByteArray()))
    }

    @Test fun version_mismatch_decodes_to_null() {
        val text = DecryptedContactJson.encode(contact).decodeToString().replaceFirst("\"v\":1", "\"v\":2")

        assertNull(DecryptedContactJson.decode(text.encodeToByteArray()))
    }
}
