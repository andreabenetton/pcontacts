// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.sync.contacts.merge

import io.pcontacts.core.protoncontacts.DecryptedContact
import io.pcontacts.core.protoncontacts.DecryptedContactJson
import io.pcontacts.core.protoncontacts.PhotoHash
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

    /**
     * No-op for a contact the projection cannot represent: it has no local
     * row to merge. The base records the server photo's digest (from the
     * contact, or of its bytes when it is the payload we just pushed) and
     * the local photo's digest as read back from the provider.
     */
    suspend fun save(
        store: MergeBaseStore,
        protonContactId: String,
        contact: DecryptedContact,
        localPhotoHash: String? = contact.localPhotoHash
    ) {
        val canonical = canonical(contact) ?: return
        val base = canonical.copy(
            serverPhotoHash = contact.serverPhotoHash ?: contact.photo?.let { PhotoHash.of(it.data) },
            localPhotoHash = localPhotoHash
        )
        store.save(protonContactId, DecryptedContactJson.encode(base))
    }

    /** The row-shaped view of [contact]; the photo digests and the carrier cards ride along untouched. */
    fun canonical(contact: DecryptedContact): DecryptedContact? =
        DecryptedContactToRow.convert(contact)?.let { row ->
            RowToDecryptedContact.convert(row, contact.protonContactId, contact.protonUid).copy(
                serverPhotoHash = contact.serverPhotoHash,
                localPhotoHash = contact.localPhotoHash,
                cards = contact.cards
            )
        }
}
