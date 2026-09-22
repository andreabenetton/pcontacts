// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.sync.contacts.merge

import io.pcontacts.core.protoncontacts.DecryptedContact
import io.pcontacts.core.protoncontacts.DecryptedContactJson
import io.pcontacts.core.storage.MergeBaseStore
import io.pcontacts.core.sync.contacts.DecryptedContactToRow
import io.pcontacts.core.sync.contacts.RowToDecryptedContact

/**
 * The merge base of ADR-0017 §3 in and out of a [MergeBaseStore], in
 * the one shape every merge input must have: what a contact looks like
 * after a round trip through the `ContactRow` projection. Local state
 * always arrives that way (it is read from ContactsContract), so the
 * base and the server state are pushed through the same projection
 * before they are compared — a field the projection cannot represent
 * then never reads as a change.
 */
internal object MergeBaseCodec {

    suspend fun load(store: MergeBaseStore, protonContactId: String): DecryptedContact? =
        store.load(protonContactId)?.let(DecryptedContactJson::decode)

    /** No-op for a contact the projection cannot represent (no email): it has no local row to merge. */
    suspend fun save(store: MergeBaseStore, protonContactId: String, contact: DecryptedContact) {
        val canonical = canonical(contact) ?: return
        store.save(protonContactId, DecryptedContactJson.encode(canonical))
    }

    fun canonical(contact: DecryptedContact): DecryptedContact? =
        DecryptedContactToRow.convert(contact)?.let { row ->
            RowToDecryptedContact.convert(row, contact.protonContactId, contact.protonUid)
        }
}
