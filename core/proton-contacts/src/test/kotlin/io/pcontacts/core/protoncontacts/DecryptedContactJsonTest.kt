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
        val text = DecryptedContactJson.encode(contact).decodeToString().replaceFirst("\"v\":3", "\"v\":1")

        assertNull(DecryptedContactJson.decode(text.encodeToByteArray()))
    }

    // ---- a base saved before the phone's photo digest could say "not known" (review 2026-11-18) ----

    private fun asVersion2(contact: DecryptedContact): ByteArray {
        val text = DecryptedContactJson.encode(contact).decodeToString()
        assertEquals("precondition: the envelope is version 3", true, text.contains("\"v\":3"))
        return text.replaceFirst("\"v\":3", "\"v\":2").encodeToByteArray()
    }

    @Test fun an_older_base_without_a_phone_photo_digest_reads_as_not_known() {
        // 2.6.0 saved "none" both for "no photo" and for "the row was edited, not known".
        val decoded = DecryptedContactJson.decode(asVersion2(contact.copy(serverPhotoHash = "ab".repeat(32))))

        assertEquals(PhotoHash.UNKNOWN, decoded?.localPhotoHash)
        assertEquals("the rest of the base is kept", "ab".repeat(32), decoded?.serverPhotoHash)
        assertEquals(contact.phones, decoded?.phones)
    }

    @Test fun an_older_base_with_a_phone_photo_digest_keeps_it() {
        val decoded = DecryptedContactJson.decode(asVersion2(contact.copy(localPhotoHash = "cd".repeat(32))))

        assertEquals("cd".repeat(32), decoded?.localPhotoHash)
    }

    @Test fun a_current_base_tells_no_photo_from_not_known() {
        val none = DecryptedContactJson.decode(DecryptedContactJson.encode(contact.copy(localPhotoHash = null)))
        val unknown = DecryptedContactJson.decode(
            DecryptedContactJson.encode(contact.copy(localPhotoHash = PhotoHash.UNKNOWN))
        )

        assertNull(none?.localPhotoHash)
        assertEquals(PhotoHash.UNKNOWN, unknown?.localPhotoHash)
    }

    @Test fun photo_digests_round_trip_without_the_bytes() {
        val withDigests = contact.copy(serverPhotoHash = "ab".repeat(32), localPhotoHash = "cd".repeat(32))

        val decoded = DecryptedContactJson.decode(DecryptedContactJson.encode(withDigests))

        assertEquals("ab".repeat(32), decoded?.serverPhotoHash)
        assertEquals("cd".repeat(32), decoded?.localPhotoHash)
        assertNull(decoded?.photo)
    }
}
