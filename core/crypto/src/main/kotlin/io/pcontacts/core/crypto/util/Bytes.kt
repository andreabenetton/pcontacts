// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.crypto.util

import java.math.BigInteger
import java.security.MessageDigest

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

internal fun String.fromHex(): ByteArray {
    require(length % 2 == 0) { "hex string must have even length" }
    return ByteArray(length / 2) { ((Character.digit(this[it * 2], 16) shl 4) + Character.digit(this[it * 2 + 1], 16)).toByte() }
}

/**
 * Returns `value` as an unsigned big-endian byte array of exactly `lengthBytes`.
 * SRP requires fixed-width inputs to its hash mixing functions ("PAD" in
 * RFC 5054 §3); this is that primitive.
 */
internal fun BigInteger.toUnsignedBytes(lengthBytes: Int): ByteArray {
    val raw = this.toByteArray()
    // BigInteger.toByteArray returns the minimum number of bytes in two's complement,
    // sometimes prefixed with 0x00 to signify sign. Strip the leading sign byte if
    // present, then left-pad with zeros to lengthBytes.
    val stripped = if (raw.isNotEmpty() && raw[0] == 0.toByte() && raw.size > 1) {
        raw.copyOfRange(1, raw.size)
    } else {
        raw
    }
    require(stripped.size <= lengthBytes) {
        "BigInteger does not fit in $lengthBytes bytes (was ${stripped.size})"
    }
    if (stripped.size == lengthBytes) return stripped
    val padded = ByteArray(lengthBytes)
    System.arraycopy(stripped, 0, padded, lengthBytes - stripped.size, stripped.size)
    return padded
}

internal fun ByteArray.toUnsignedBigInteger(): BigInteger = BigInteger(1, this)

internal fun sha512(vararg parts: ByteArray): ByteArray {
    val md = MessageDigest.getInstance("SHA-512")
    for (p in parts) md.update(p)
    return md.digest()
}
