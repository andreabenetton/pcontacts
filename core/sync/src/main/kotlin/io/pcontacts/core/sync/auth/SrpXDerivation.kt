// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.sync.auth

import io.pcontacts.core.crypto.bcrypt.SrpHashPassword
import java.math.BigInteger

/**
 * SRP-variant `x` derivation for Proton (version 4).
 *
 * `[V]` Algorithm verified against `@protontech/crypto` v2.0.1 source
 * and captured vectors (tools/vectors/). Delegates to
 * [SrpHashPassword.derive] which implements the exact `hashPassword3
 * + formatHash + expandHash` pipeline from `passwords.ts`.
 */
object SrpXDerivation {

    /**
     * @param password     user's plaintext password.
     * @param srpSaltB64   base64 salt from `auth/info` — used as a CHARACTER
     *                     string (not decoded to bytes).
     * @param modulusBytes decoded SRP modulus (N) bytes.
     * @return SRP `x` = BigInteger(1, hashPassword(password, salt, modulus)).
     */
    fun deriveX(password: CharArray, srpSaltB64: String, modulusBytes: ByteArray): BigInteger {
        val expanded = SrpHashPassword.derive(password, srpSaltB64, modulusBytes)
        return BigInteger(1, expanded)
    }
}
