// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.protoncontacts

import java.security.MessageDigest

/** SHA-256 of photo bytes as lowercase hex: the photo's identity in the merge base (ADR-0017). */
object PhotoHash {
    fun of(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
