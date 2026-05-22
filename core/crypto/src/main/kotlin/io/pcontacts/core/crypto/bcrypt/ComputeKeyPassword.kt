// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.crypto.bcrypt

import io.pcontacts.core.crypto.util.sha512
import java.util.Base64
import org.bouncycastle.crypto.generators.OpenBSDBCrypt

/**
 * Derives the mailbox key-password from the user's plaintext password and
 * the per-key salt (`User.Keys[0].KeySalt` in the Proton API).
 *
 * Algorithm — `bcrypt(base64(SHA-512(password)), salt, cost)` — is the
 * "bcrypt-SHA-512" pattern Proton documents publicly. Proton-specific
 * details that are **assumptions** until validated against `@protontech/crypto`:
 *
 *   `[A]` Cost factor = 10 (Proton's published documentation; not exhaustively
 *         verified in the WebClients source).
 *   `[A]` Pre-hash form = base64 of raw SHA-512 bytes. Some bcrypt-SHA-512
 *         variants take the raw 64 bytes directly; we use the base64 form
 *         (matches the Dropbox bcrypt-SHA512 pattern that Proton's
 *         documentation cites as the inspiration).
 *   `[A]` Output = the full `$2y$10$...` BcryptString. Callers that need
 *         only the trailing 31-character hash slice can use [trailingHash].
 *
 * All assumptions will be replaced with `[V]` once ADR-0013 vectors land.
 */
object ComputeKeyPassword {

    private const val COST: Int = 10
    private const val BCRYPT_SALT_BYTES: Int = 16

    /**
     * @param password user's plaintext mailbox password (UTF-8 encoded).
     * @param keySaltB64 base64-encoded 16-byte salt from `User.Keys[0].KeySalt`.
     * @return the bcrypt string in OpenBSD `$2y$10$saltOhash` form.
     */
    fun derive(password: CharArray, keySaltB64: String): String {
        val saltBytes = decodeSalt(keySaltB64)
        val preHash = base64(sha512(passwordBytes(password)))
        return OpenBSDBCrypt.generate(preHash.toCharArray(), saltBytes, COST)
    }

    /** Returns just the 31-character hash portion (the SRP `x` input). */
    fun trailingHash(bcryptString: String): String =
        bcryptString.substringAfterLast('$').substring(22)

    private fun passwordBytes(password: CharArray): ByteArray {
        // Convert CharArray to UTF-8 bytes WITHOUT String allocation so the
        // raw password never sits in a String literal pool.
        val cb = java.nio.CharBuffer.wrap(password)
        val bb = Charsets.UTF_8.newEncoder().encode(cb)
        val out = ByteArray(bb.remaining())
        bb.get(out)
        return out
    }

    private fun decodeSalt(keySaltB64: String): ByteArray {
        val raw = Base64.getDecoder().decode(keySaltB64)
        require(raw.size == BCRYPT_SALT_BYTES) {
            "KeySalt must decode to $BCRYPT_SALT_BYTES bytes, was ${raw.size}"
        }
        return raw
    }

    private fun base64(bytes: ByteArray): String =
        Base64.getEncoder().withoutPadding().encodeToString(bytes)
}
