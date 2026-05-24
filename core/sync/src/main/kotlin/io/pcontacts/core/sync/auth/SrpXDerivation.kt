// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.sync.auth

import io.pcontacts.core.crypto.bcrypt.SrpHashPassword
import java.math.BigInteger

/**
 * SRP-variant `x` derivation for Proton (version 4).
 *
 * `[V]` Algorithm verified against `ProtonMail/go-srp` and
 * `@protontech/crypto` v2.0.1 source + captured vectors.
 *
 * The `hashPassword` output is a 256-byte expandHash result. go-srp
 * interprets this as little-endian via `toNat()` — reverse before
 * constructing the BigInteger.
 */
object SrpXDerivation {

    /**
     * @param password     user's plaintext password.
     * @param srpSaltB64   base64 salt from `auth/info` — used as a CHARACTER
     *                     string (not decoded to bytes).
     * @param modulusBytes raw SRP modulus bytes as received from the API
     *                     (little-endian wire format, not reversed).
     * @return SRP `x` = fromLittleEndian(hashPassword(password, salt, modulus)).
     */
    fun deriveX(password: CharArray, srpSaltB64: String, modulusBytes: ByteArray): BigInteger {
        val expanded = SrpHashPassword.derive(password, srpSaltB64, modulusBytes)
        return BigInteger(1, expanded.reversedArray())
    }
}
