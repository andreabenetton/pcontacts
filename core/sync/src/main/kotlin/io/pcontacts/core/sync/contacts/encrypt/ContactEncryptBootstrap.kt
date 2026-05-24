// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.sync.contacts.encrypt

import io.pcontacts.core.crypto.openpgp.OpenPgpService
import io.pcontacts.core.crypto.openpgp.UnlockedKey
import io.pcontacts.core.protoncontacts.ContactSerializer

/**
 * Builds a sync-run-scoped [ContactSerializer] from an already-unlocked
 * key ring. The write-path counterpart of
 * [io.pcontacts.core.sync.contacts.decrypt.ContactDecryptBootstrap].
 *
 * Unlike the decrypt bootstrap, this does NOT perform key unlock — the
 * caller ([SyncBootstrap.createBidirectionalEngines]) unlocks once and
 * shares the [UnlockedKey] between decrypt and encrypt paths.
 *
 * Self-encryption: contacts are encrypted to the user's own public key
 * and signed with the user's primary private key. `[V]`
 */
object ContactEncryptBootstrap {

    fun createSerializer(
        openPgp: OpenPgpService,
        unlocked: UnlockedKey
    ): ContactSerializer {
        val encryptOp = OpenPgpCardEncryptOp.build(
            openPgp = openPgp,
            encryptionKeys = listOf(unlocked.public),
            signingKey = unlocked.private
        )
        return ContactSerializer(encryptOp = encryptOp)
    }
}
