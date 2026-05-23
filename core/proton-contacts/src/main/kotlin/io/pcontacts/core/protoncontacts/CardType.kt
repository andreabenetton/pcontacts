// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.protoncontacts

/**
 * Mirror of `packages/shared/lib/contacts/constants.ts CONTACT_CARD_TYPE`
 * [V]. Wire values are integers; never reorder.
 *
 * Per Plan §10.3 the merge logic discards vCard UID properties that come
 * from non-SIGNED cards (otherwise a tampered ENCRYPTED card could
 * rebind a contact's identity), so the original card type is preserved
 * downstream alongside the decrypted plaintext.
 */
enum class CardType(val wireValue: Int) {
    CLEAR_TEXT(0),
    ENCRYPTED(1),
    SIGNED(2),
    ENCRYPTED_AND_SIGNED(3);

    companion object {
        fun fromWire(value: Int): CardType? = entries.firstOrNull { it.wireValue == value }
    }
}
