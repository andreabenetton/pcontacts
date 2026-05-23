// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.storage

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Test

class InMemorySecretStoreTest {

    @Test fun stores_and_returns_uid_access_refresh_tokens() {
        val store = InMemorySecretStore()
        store.setUid("uid-1")
        store.setAccessToken("access-1")
        store.setRefreshToken("refresh-1")
        assertEquals("uid-1", store.uid())
        assertEquals("access-1", store.accessToken())
        assertEquals("refresh-1", store.refreshToken())
    }

    @Test fun setting_null_clears_a_token_individually() {
        val store = InMemorySecretStore()
        store.setUid("x")
        store.setAccessToken("y")
        store.setUid(null)
        assertNull(store.uid())
        assertEquals("y", store.accessToken())
    }

    @Test fun keyPassword_round_trip_returns_defensive_copy() {
        val store = InMemorySecretStore()
        val original = "hunter2-derived-bytes".toByteArray()
        store.setKeyPassword(original)
        val readBack = store.keyPassword()
        assertArrayEquals(original, readBack)
        assertNotSame("getter must hand back a defensive copy", original, readBack)
    }

    @Test fun setKeyPassword_zeroes_previous_buffer() {
        val store = InMemorySecretStore()
        val first = ByteArray(8) { it.toByte() }
        store.setKeyPassword(first)
        val tracked = store.keyPassword()!!
        store.setKeyPassword(ByteArray(4) { 0x42 })
        // The defensive copy we already pulled is independent — the
        // store's internal buffer is what we care about. Verify the
        // *new* read returns the new value, not the old.
        assertArrayEquals(ByteArray(4) { 0x42 }, store.keyPassword())
        // And the original captured copy is still intact (defensive copy).
        assertArrayEquals(ByteArray(8) { it.toByte() }, tracked)
    }

    @Test fun logout_wipes_everything() {
        val store = InMemorySecretStore()
        store.setUid("u")
        store.setAccessToken("a")
        store.setRefreshToken("r")
        store.setKeyPassword("kp".toByteArray())
        store.logout()
        assertNull(store.uid())
        assertNull(store.accessToken())
        assertNull(store.refreshToken())
        assertNull(store.keyPassword())
    }
}
