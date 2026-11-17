// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.sync.contacts.merge

import io.pcontacts.core.protoncontacts.CardType
import io.pcontacts.core.protoncontacts.DecryptedCard
import io.pcontacts.core.protoncontacts.DecryptedContact
import io.pcontacts.core.protoncontacts.DecryptedEmail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MergeBaseCodecTest {

    @Test fun the_row_shaped_view_keeps_the_trust_state_of_proton_copy() {
        // Review 2026-11-17, F3 and F7: the row has no trust state, and the projection used to
        // reset it to "verified", so a failed signature vanished on the way into the merge.
        val failed = DecryptedContact(
            protonContactId = "ct-1",
            protonUid = "uid-1",
            fullName = "Alice",
            emails = listOf(DecryptedEmail(address = "alice@proton.me", isPrimary = true)),
            verified = false,
            signed = true,
            cardCount = 2,
            unverifiedCardCount = 1,
            cards = listOf(DecryptedCard(CardType.SIGNED, "BEGIN:VCARD\r\nEND:VCARD", verified = false))
        )

        val canonical = MergeBaseCodec.canonical(failed)!!

        assertFalse(canonical.verified)
        assertTrue(canonical.signed)
        assertEquals(2, canonical.cardCount)
        assertEquals(1, canonical.unverifiedCardCount)
        assertEquals(failed.cards, canonical.cards)
    }

    @Test fun an_unsigned_contact_stays_unsigned_in_the_row_shaped_view() {
        val unsigned = DecryptedContact(
            protonContactId = "ct-2",
            protonUid = "uid-2",
            fullName = "Bob",
            emails = emptyList(),
            verified = false,
            signed = false,
            cardCount = 1,
            unverifiedCardCount = 0
        )

        val canonical = MergeBaseCodec.canonical(unsigned)!!

        assertFalse(canonical.signed)
        assertFalse(canonical.verified)
    }
}
