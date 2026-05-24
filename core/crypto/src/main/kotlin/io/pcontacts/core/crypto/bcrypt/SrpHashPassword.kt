// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.crypto.bcrypt

import io.pcontacts.core.crypto.util.expandHash
import java.util.Base64
import org.bouncycastle.crypto.generators.OpenBSDBCrypt

/**
 * Proton SRP `hashPassword` (version 4) — derives the 256-byte value
 * used as the SRP `x` parameter.
 *
 * Algorithm (verified `[V]` against `ProtonMail/go-srp` `hashPasswordVersion3`):
 *
 *   1. `rawSalt = base64Decode(saltB64)` — decode salt from base64 to raw bytes.
 *   2. `saltWithSuffix = rawSalt ‖ bytes("proton")` — append ASCII "proton".
 *   3. `bcryptSalt = first16(saltWithSuffix)` — take the first 16 bytes.
 *   4. `unexpandedHash = bcrypt(password, "$2y$10$" + bcryptEncode(bcryptSalt))`
 *      → full 60-character bcrypt string.
 *   5. `hashBytes = charCodeBytes(unexpandedHash)` — each char → its byte value.
 *   6. `concat = hashBytes ‖ modulusBytes`.
 *   7. `expandHash(concat)` = `SHA-512(concat‖0x00) ‖ SHA-512(concat‖0x01)
 *      ‖ SHA-512(concat‖0x02) ‖ SHA-512(concat‖0x03)` → 256 bytes.
 *
 * Source: `ProtonMail/go-srp` `hashPasswordVersion3` — salt is
 *         `base64Decode(saltB64) + []byte("proton")`, then first 16.
 *
 * This is NOT the same as [ComputeKeyPassword.derive] — that function
 * implements `computeKeyPassword` (key-unlock path), which uses a
 * different salt encoding and returns only the trailing hash.
 */
object SrpHashPassword {

    private const val COST: Int = 10
    private const val BCRYPT_SALT_BYTES: Int = 16

    /**
     * @param password  user's plaintext password.
     * @param saltB64   base64 salt from `auth/info` — decoded to raw bytes,
     *                  then "proton" appended, then first 16 bytes used as
     *                  the bcrypt salt (`[V]` go-srp `hashPasswordVersion3`).
     * @param modulusBytes  decoded SRP modulus (N) bytes (typically 256 bytes for
     *                      2048-bit).
     * @return 256-byte expanded hash, used as SRP `x` when interpreted as
     *         `BigInteger(1, result)`.
     */
    fun derive(password: CharArray, saltB64: String, modulusBytes: ByteArray): ByteArray {
        // Step 1-2: decode base64 salt to raw bytes, append "proton", take first 16
        val rawSalt = Base64.getDecoder().decode(saltB64)
        val saltWithSuffix = rawSalt + "proton".toByteArray(Charsets.US_ASCII)
        val bcryptSalt = saltWithSuffix.copyOfRange(0, minOf(saltWithSuffix.size, BCRYPT_SALT_BYTES))
        require(bcryptSalt.size == BCRYPT_SALT_BYTES) {
            "salt + 'proton' must yield at least $BCRYPT_SALT_BYTES bytes"
        }

        // Step 3: bcrypt with cost 10
        val unexpandedHash = OpenBSDBCrypt.generate(password, bcryptSalt, COST)

        // Step 4: convert hash string chars to byte values (charCodeAt)
        val hashBytes = charCodeBytes(unexpandedHash)

        // Step 5: concatenate with modulus
        val concat = ByteArray(hashBytes.size + modulusBytes.size)
        System.arraycopy(hashBytes, 0, concat, 0, hashBytes.size)
        System.arraycopy(modulusBytes, 0, concat, hashBytes.size, modulusBytes.size)

        // Step 6: expand (4× SHA-512 with counter byte appended)
        return expandHash(concat)
    }

    /**
     * Each character's code point as a byte — matches the JS
     * `binaryStringToUint8Array` helper. bcrypt output is pure ASCII
     * so no char exceeds 0x7F.
     */
    private fun charCodeBytes(s: String): ByteArray {
        val out = ByteArray(s.length)
        for (i in s.indices) {
            out[i] = s[i].code.toByte()
        }
        return out
    }
}
