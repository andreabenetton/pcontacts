// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.sync.contacts.encrypt

import io.pcontacts.core.crypto.openpgp.OpenPgpService
import io.pcontacts.core.crypto.openpgp.PgpPrivateKeyHandle
import io.pcontacts.core.crypto.openpgp.PgpPublicKeyHandle
import io.pcontacts.core.protoncontacts.CardEncryptOp
import io.pcontacts.core.protoncontacts.CardEncryptOutcome
import io.pcontacts.core.protoncontacts.CardEncryptRequest

/**
 * Adapts :core:crypto's [OpenPgpService] to :core:proton-contacts's
 * [CardEncryptOp] — the write-path counterpart of
 * [io.pcontacts.core.sync.contacts.decrypt.OpenPgpCardCryptoOp].
 *
 * Per-card semantics:
 *   - [CardEncryptRequest.SignOnly] → `openPgp.signDetached`; data
 *     stays plaintext, signature is armored detached PGP.
 *   - [CardEncryptRequest.EncryptAndSign] →
 *     `openPgp.encryptAndSignDetached`; data is armored ciphertext,
 *     signature is armored detached PGP.
 *
 * Contacts are self-encrypted: [encryptionKeys] contains the user's
 * own public key(s), [signingKey] is the user's primary private key.
 * `[V]` — matches web client `packages/shared/lib/contacts/encrypt.ts`.
 */
object OpenPgpCardEncryptOp {

    fun build(
        openPgp: OpenPgpService,
        encryptionKeys: List<PgpPublicKeyHandle>,
        signingKey: PgpPrivateKeyHandle
    ): CardEncryptOp = { request ->
        when (request) {
            is CardEncryptRequest.SignOnly -> {
                val sig = openPgp.signDetached(
                    plaintext = request.plaintext.toByteArray(Charsets.UTF_8),
                    signingKey = signingKey
                )
                CardEncryptOutcome(data = request.plaintext, signature = sig)
            }
            is CardEncryptRequest.EncryptAndSign -> {
                val result = openPgp.encryptAndSignDetached(
                    plaintext = request.plaintext.toByteArray(Charsets.UTF_8),
                    encryptionKeys = encryptionKeys,
                    signingKey = signingKey
                )
                CardEncryptOutcome(
                    data = result.armoredMessage,
                    signature = result.armoredDetachedSignature
                )
            }
        }
    }
}
