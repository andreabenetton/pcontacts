// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.crypto.bcrypt

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ComputeKeyPasswordTest {

    private val saltB64: String = Base64.getEncoder().encodeToString(ByteArray(16) { i -> i.toByte() })

    @Test fun derives_31_character_trailing_hash() {
        val result = ComputeKeyPassword.derive("hunter2".toCharArray(), saltB64)
        assertEquals("trailing hash must be 31 characters", 31, result.length)
    }

    @Test fun deterministic_for_same_inputs() {
        val a = ComputeKeyPassword.derive("p4ssword".toCharArray(), saltB64)
        val b = ComputeKeyPassword.derive("p4ssword".toCharArray(), saltB64)
        assertEquals(a, b)
    }

    @Test fun differs_on_different_passwords() {
        val a = ComputeKeyPassword.derive("alice".toCharArray(), saltB64)
        val b = ComputeKeyPassword.derive("bob".toCharArray(), saltB64)
        assertNotEquals(a, b)
    }

    @Test fun differs_on_different_salts() {
        val saltA = Base64.getEncoder().encodeToString(ByteArray(16) { 0x11 })
        val saltB = Base64.getEncoder().encodeToString(ByteArray(16) { 0x22 })
        val a = ComputeKeyPassword.derive("samepassword".toCharArray(), saltA)
        val b = ComputeKeyPassword.derive("samepassword".toCharArray(), saltB)
        assertNotEquals(a, b)
    }

    @Test fun unicode_password_does_not_crash() {
        val out = ComputeKeyPassword.derive("p4sséèà".toCharArray(), saltB64)
        assertEquals(31, out.length)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejects_wrong_salt_size() {
        val shortSalt = Base64.getEncoder().encodeToString(ByteArray(8))
        ComputeKeyPassword.derive("x".toCharArray(), shortSalt)
    }
}
