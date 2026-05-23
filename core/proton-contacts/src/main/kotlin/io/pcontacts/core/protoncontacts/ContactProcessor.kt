// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.protoncontacts

import io.pcontacts.core.proton.api.contacts.ContactDto

/**
 * End-to-end facade: ContactDto → DecryptedContact.
 *   1. Each Card runs through `ContactDecrypter` (dispatch + crypto-op).
 *   2. The resulting `DecryptedCard` plaintexts are parsed and merged
 *      by `VCardMerger` into a single Kotlin-native model.
 *
 * This is the only entry point :core:sync should call once the
 * decrypt-aware engine ships — keeps Card semantics in one place.
 */
class ContactProcessor(
    private val decrypter: ContactDecrypter
) {
    private val merger = VCardMerger()

    fun process(contact: ContactDto): DecryptedContact {
        val decrypted = decrypter.decryptCards(contact.cards)
        return merger.merge(protonContactId = contact.id, decrypted = decrypted)
    }
}
