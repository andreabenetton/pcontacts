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

/**
 * `[V]` Proton's `expandHash` — 4× SHA-512 with a one-byte counter
 * appended, yielding 256 bytes. Used by both `hashPassword` (SRP x
 * derivation) and the SRP protocol itself (k, u, M1, M2).
 *
 * Matches `go-srp/hash.go:expandHash` and
 * `@protontech/crypto/src/srp/passwords.ts:expandHash`.
 */
internal fun expandHash(input: ByteArray): ByteArray {
    val result = ByteArray(4 * 64)
    for (i in 0 until 4) {
        val md = MessageDigest.getInstance("SHA-512")
        md.update(input)
        md.update(i.toByte())
        System.arraycopy(md.digest(), 0, result, i * 64, 64)
    }
    return result
}

/**
 * `[V]` Converts a BigInteger to a little-endian byte array of exactly
 * `lengthBytes`, matching go-srp's `fromNat(bitLength, nat)`.
 */
internal fun BigInteger.toLittleEndianBytes(lengthBytes: Int): ByteArray =
    toUnsignedBytes(lengthBytes).reversedArray()

/**
 * `[V]` Interprets a little-endian byte array as an unsigned BigInteger,
 * matching go-srp's `toNat(buf)`.
 */
internal fun ByteArray.fromLittleEndianBigInteger(): BigInteger =
    BigInteger(1, this.reversedArray())
