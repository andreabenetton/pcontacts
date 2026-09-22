// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.storage

import io.pcontacts.core.logging.Logger
import io.pcontacts.core.logging.NoOpSink
import io.pcontacts.core.logging.RedactingLogger
import io.pcontacts.core.storage.db.dao.ContactMapDao
import java.security.GeneralSecurityException

/**
 * The per-contact merge base of ADR-0017 §3: the last-known server
 * state as opaque bytes. The sync engine sees plaintext; the store
 * decides how it rests. Production seals every blob under the Keystore
 * KEK before it reaches Room (ADR-0018); tests keep it in memory.
 */
interface MergeBaseStore {

    /** Null when no base is stored or the stored one cannot be opened. */
    suspend fun load(protonContactId: String): ByteArray?

    /** No-op when the contact has no mapping row. */
    suspend fun save(protonContactId: String, plaintext: ByteArray)
}

/** `contact_map.last_known_server_payload`, sealed with [SecretCipher] (the `pcontacts.kekv1` key). */
class KeystoreMergeBaseStore(
    private val dao: ContactMapDao,
    private val cipher: SecretCipher = KeystoreAesGcmKek(),
    private val logger: Logger = RedactingLogger(tag = "MergeBase", sink = NoOpSink)
) : MergeBaseStore {

    override suspend fun load(protonContactId: String): ByteArray? {
        val sealed = dao.mergeBase(protonContactId) ?: return null
        return try {
            cipher.unwrap(sealed)
        } catch (e: GeneralSecurityException) {
            logger.warn(e) { "merge base unreadable; treating as absent" }
            null
        } catch (e: IllegalArgumentException) {
            logger.warn(e) { "merge base malformed; treating as absent" }
            null
        }
    }

    override suspend fun save(protonContactId: String, plaintext: ByteArray) {
        dao.setMergeBase(protonContactId, cipher.wrap(plaintext))
    }
}

/** Test double: plaintext in a map, no database. */
class InMemoryMergeBaseStore : MergeBaseStore {
    private val bases = HashMap<String, ByteArray>()

    override suspend fun load(protonContactId: String): ByteArray? = bases[protonContactId]?.copyOf()

    override suspend fun save(protonContactId: String, plaintext: ByteArray) {
        bases[protonContactId] = plaintext.copyOf()
    }

    fun remove(protonContactId: String) {
        bases.remove(protonContactId)
    }
}
