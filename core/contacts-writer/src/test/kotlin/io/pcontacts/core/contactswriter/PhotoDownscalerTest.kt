// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.contactswriter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pure tests for the no-op / null paths. The actual BitmapFactory +
 * compress() path is best validated on an emulator — Robolectric's
 * Bitmap shadow is brittle around quality/format compression
 * fidelity, so we don't assert on the resized byte length here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class PhotoDownscalerTest {

    @Test fun empty_input_returns_null() {
        assertNull(PhotoDownscaler.downscale(ByteArray(0)))
    }

    @Test fun bytes_already_under_the_cap_pass_through_unchanged() {
        val small = ByteArray(100) { it.toByte() }
        val out = PhotoDownscaler.downscale(small)
        assertSame("under-cap input must be returned without re-encoding", small, out)
    }

    @Test fun cap_constant_matches_ContactsContract_inline_photo_limit() {
        // Soft cap documented at ~96KB; pin the constant so accidental
        // bumps land in code review.
        assertEquals(96 * 1024, PhotoDownscaler.MAX_INLINE_PHOTO_BYTES)
    }

    // Note: a "garbage bytes return null" test would fit here but
    // Robolectric's BitmapFactory shadow returns a stub Bitmap for
    // any input including non-image bytes — so the assertion only
    // holds on a real device. Production behavior: on-device
    // BitmapFactory.decodeByteArray returns null for undecodable
    // input and PhotoDownscaler propagates the null without
    // throwing (the catch path in tryFit).
}
