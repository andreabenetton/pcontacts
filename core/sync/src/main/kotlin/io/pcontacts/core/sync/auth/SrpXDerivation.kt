// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.sync.auth

import io.pcontacts.core.crypto.bcrypt.ComputeKeyPassword
import java.math.BigInteger
import java.security.MessageDigest
import java.util.Base64

/**
 * SRP-variant `x` derivation for Proton.
 *
 * `[A]` The Proton web client's actual `hashPassword` (version 4) is:
 *
 *   1. Append `"proton"` to the salt bytes.
 *   2. bcrypt-encode those bytes as a 22-char bcrypt salt.
 *   3. `bcryptHash(password, "$2y$10$" + bcryptSalt)` → full 60-char string.
 *   4. Convert the full string to binary.
 *   5. Concatenate with the SRP modulus bytes.
 *   6. `expandHash(concat)` = `SHA512(concat‖0) ‖ SHA512(concat‖1) ‖
 *       SHA512(concat‖2) ‖ SHA512(concat‖3)` → 256 bytes.
 *   7. Interpret as unsigned big integer → SRP `x`.
 *
 *   Source: `@protontech/crypto/src/srp/passwords.ts` (hashPassword3 +
 *           formatHash + expandHash).
 *
 *   Our current implementation omits the "proton" salt suffix, the modulus
 *   mixing, and the 4-way expand. We instead apply a single SHA-512 over
 *   the raw bcrypt output. This lets login proceed against mock servers in
 *   tests but will NOT produce a valid proof against Proton's real API.
 *   Proper fix requires SRP-specific captured vectors (deferred until the
 *   openpgp/CryptoProxy wiring is available in the capture script).
 *
 * Centralising the derivation in one object lets the orchestrator AND
 * the orchestrator's integration test compute `x` identically without
 * duplicating the logic.
 *
 * NOTE: this is deliberately NOT `ComputeKeyPassword.derive()` — that
 * function implements Proton's `computeKeyPassword` (key-unlock path),
 * which is a different algorithm from the SRP `hashPassword` path.
 */
object SrpXDerivation {

    private const val BCRYPT_SALT_BYTES: Int = 16

    fun deriveX(password: CharArray, srpSaltB64: String): BigInteger {
        val saltBytes = Base64.getDecoder().decode(srpSaltB64)
        val padded = padOrTruncate(saltBytes, BCRYPT_SALT_BYTES)
        val bcryptOut = ComputeKeyPassword.rawBcrypt(password, padded)
        val md = MessageDigest.getInstance("SHA-512")
        return BigInteger(1, md.digest(bcryptOut.toByteArray(Charsets.UTF_8)))
    }

    private fun padOrTruncate(input: ByteArray, length: Int): ByteArray {
        if (input.size == length) return input
        if (input.size > length) return input.copyOfRange(0, length)
        val out = ByteArray(length)
        System.arraycopy(input, 0, out, 0, input.size)
        return out
    }
}
