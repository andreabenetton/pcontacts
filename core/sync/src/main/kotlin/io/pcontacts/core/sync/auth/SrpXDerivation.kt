// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.sync.auth

import io.pcontacts.core.crypto.bcrypt.ComputeKeyPassword
import java.math.BigInteger
import java.security.MessageDigest

/**
 * SRP-variant `x` derivation for Proton.
 *
 * `[A]` Without captured `@protontech/crypto/srp` vectors we cannot prove
 * the exact derivation Proton uses. We currently apply
 * `x = BigInteger(1, SHA-512(bcrypt-SHA-512(password, salt).bytes))`,
 * which matches the family of constructions Proton's public docs cite
 * (Dropbox-style bcrypt-SHA-512 plus a SHA-512 reduction step) but
 * remains an assumption until ADR-0013 vectors land.
 *
 * Centralising the derivation in one object lets the orchestrator AND
 * the orchestrator's integration test compute `x` identically without
 * duplicating the logic — that's the only way the test's pre-computed
 * SRP exchange and the orchestrator's runtime call agree.
 */
object SrpXDerivation {

    fun deriveX(password: CharArray, srpSaltB64: String): BigInteger {
        val bcryptOut = ComputeKeyPassword.derive(password, srpSaltB64)
        val md = MessageDigest.getInstance("SHA-512")
        return BigInteger(1, md.digest(bcryptOut.toByteArray(Charsets.UTF_8)))
    }
}
