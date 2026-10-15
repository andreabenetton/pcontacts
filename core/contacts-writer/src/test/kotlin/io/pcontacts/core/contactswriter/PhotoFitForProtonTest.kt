// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.contactswriter

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

/** A photo sent to Proton is fitted as Proton's own client fits it (ADR-0017, 2026-10-15). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoFitForProtonTest {

    private fun image(width: Int, height: Int, format: Bitmap.CompressFormat): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(40, 120, 200))
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(format, FULL_QUALITY, out)
            out.toByteArray()
        }
    }

    private fun bounds(bytes: ByteArray) = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, this)
    }

    @Test fun a_large_photo_is_fitted_to_180_px_as_jpeg_keeping_its_shape() {
        val fitted = PhotoDownscaler.fitForProton(image(1440, 720, Bitmap.CompressFormat.PNG))!!

        val out = bounds(fitted)
        assertEquals("image/jpeg", out.outMimeType)
        assertEquals(180, out.outWidth)
        assertEquals(90, out.outHeight)
    }

    @Test fun a_small_png_is_sent_as_jpeg_at_its_own_size() {
        val out = bounds(PhotoDownscaler.fitForProton(image(120, 80, Bitmap.CompressFormat.PNG))!!)

        assertEquals("image/jpeg", out.outMimeType)
        assertEquals(120 to 80, out.outWidth to out.outHeight)
    }

    @Test fun a_jpeg_already_that_small_is_sent_as_it_is() {
        val small = image(180, 180, Bitmap.CompressFormat.JPEG)

        assertSame(small, PhotoDownscaler.fitForProton(small))
    }

    @Test fun bytes_that_are_no_image_give_no_photo() {
        assertNull(PhotoDownscaler.fitForProton(byteArrayOf(1, 2, 3, 4)))
    }

    @Test fun the_box_is_protons_contact_image_size() {
        assertTrue(PhotoDownscaler.PROTON_PHOTO_SIZE == 180)
    }

    private companion object {
        const val FULL_QUALITY = 100
    }
}
