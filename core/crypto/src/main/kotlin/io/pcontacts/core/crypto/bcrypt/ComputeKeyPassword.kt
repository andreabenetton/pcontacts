// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.crypto.bcrypt

import java.util.Base64
import org.bouncycastle.crypto.generators.OpenBSDBCrypt

/**
 * Derives the mailbox key-password from the user's plaintext password and
 * the per-key salt (`User.Keys[0].KeySalt` in the Proton API).
 *
 * Algorithm — `bcrypt(password, salt, cost=10)` then strip the first 29
 * characters (prefix + encoded salt), returning only the 31-character
 * hash. This matches `@protontech/crypto/src/srp/keys.ts`
 * `computeKeyPassword` exactly.
 *
 *   `[V]` No SHA-512 pre-hash — the raw UTF-8 password goes into bcrypt.
 *         Verified against `@protontech/crypto` v2.0.1 source + captured
 *         vectors (tools/vectors/).
 *   `[V]` Cost factor = 10 (`BCRYPT_PREFIX = "$2y$10$"` in constants.ts).
 *   `[V]` Output = trailing hash only (`.slice(29)` in JS source).
 */
object ComputeKeyPassword {

    private const val COST: Int = 10
    private const val BCRYPT_SALT_BYTES: Int = 16
    private const val BCRYPT_PREFIX_LEN: Int = 29 // "$2y$10$" (7) + encoded-salt (22)

    /**
     * @param password user's plaintext mailbox password (UTF-8 encoded).
     * @param keySaltB64 base64-encoded 16-byte salt from `User.Keys[0].KeySalt`.
     * @return the 31-character trailing hash portion of the bcrypt output —
     *         the actual key password used to unlock PGP private keys.
     */
    fun derive(password: CharArray, keySaltB64: String): String {
        val saltBytes = decodeSalt(keySaltB64)
        val full = OpenBSDBCrypt.generate(password, saltBytes, COST)
        return full.substring(BCRYPT_PREFIX_LEN)
    }

    /**
     * Raw `$2y$10$…` bcrypt string (full 60-char output, not sliced).
     * Used by the SRP `x` derivation path which needs the complete
     * bcrypt output for further hashing — NOT by the key-unlock path.
     */
    fun rawBcrypt(password: CharArray, saltBytes: ByteArray): String {
        require(saltBytes.size == BCRYPT_SALT_BYTES) {
            "bcrypt salt must be $BCRYPT_SALT_BYTES bytes, was ${saltBytes.size}"
        }
        return OpenBSDBCrypt.generate(password, saltBytes, COST)
    }

    private fun decodeSalt(keySaltB64: String): ByteArray {
        val raw = Base64.getDecoder().decode(keySaltB64)
        require(raw.size == BCRYPT_SALT_BYTES) {
            "KeySalt must decode to $BCRYPT_SALT_BYTES bytes, was ${raw.size}"
        }
        return raw
    }
}
