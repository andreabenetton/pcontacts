// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.protoncontacts

import java.security.MessageDigest

/** SHA-256 of photo bytes as lowercase hex: the photo's identity in the merge base (ADR-0017). */
object PhotoHash {

    /**
     * In place of a digest in the merge base's `localPhotoHash`: what the phone held at the time
     * of the base is not known. Not the same as null, "the phone had no photo": a photo removed
     * since then would read as never there (review 2026-11-18, R1). Never a digest itself.
     */
    const val UNKNOWN = "unknown"

    fun of(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
