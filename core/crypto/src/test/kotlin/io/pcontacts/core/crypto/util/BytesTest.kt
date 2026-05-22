// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.crypto.util

import java.math.BigInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class BytesTest {

    @Test fun hex_round_trip() {
        val original = byteArrayOf(0x00, 0x01, 0x7f.toByte(), 0xff.toByte(), 0xab.toByte())
        val hex = original.toHex()
        assertEquals("00017fffab", hex)
        assertArrayEquals(original, hex.fromHex())
    }

    @Test fun pads_short_BigInteger_with_leading_zeros() {
        val n = BigInteger.valueOf(0x1234)
        val bytes = n.toUnsignedBytes(8)
        assertEquals(8, bytes.size)
        assertArrayEquals(
            byteArrayOf(0, 0, 0, 0, 0, 0, 0x12, 0x34),
            bytes
        )
    }

    @Test fun strips_BigInteger_sign_byte_before_padding() {
        // 0xFF would otherwise be encoded as 0x00 0xFF (positive); strip the
        // sign byte then pad to width.
        val n = BigInteger.valueOf(0xFFL)
        val bytes = n.toUnsignedBytes(4)
        assertArrayEquals(byteArrayOf(0, 0, 0, 0xFF.toByte()), bytes)
    }

    @Test fun unsigned_round_trip_through_BigInteger() {
        val original = byteArrayOf(0xff.toByte(), 0x80.toByte(), 0x01, 0x00)
        val n = original.toUnsignedBigInteger()
        assertEquals(BigInteger("FF800100", 16), n)
        assertArrayEquals(original, n.toUnsignedBytes(4))
    }
}
