// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.contactswriter

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

/**
 * Downscales contact photo bytes to fit ContactsContract's inline
 * `Photo.PHOTO` BLOB column. Plan §8 / §12.5 — the inline column
 * accepts ≤ ~96KB; larger payloads either silently fail at the
 * provider or land in `RawContacts.DisplayPhoto` via an output stream
 * (the latter is deferred to the complete version).
 *
 * Strategy:
 *   1. If the input is already under the byte cap, pass through.
 *   2. Otherwise decode the bitmap, re-encode as JPEG at decreasing
 *      quality until the bytes fit OR quality bottoms out.
 *   3. If a single re-encode pass still doesn't fit, halve the
 *      dimensions and try again.
 *   4. Last-resort: drop the photo (return null) — better no thumbnail
 *      than a write that breaks the whole RawContact apply.
 *
 * Pure side-effect-free function; tests can run under Robolectric
 * (BitmapFactory has a Robolectric shadow). The current production
 * path tolerates either Robolectric or device execution.
 */
object PhotoDownscaler {

    /** ContactsContract's documented soft cap for the inline Photo column. */
    const val MAX_INLINE_PHOTO_BYTES = 96 * 1024

    /** Bottom of the JPEG quality slide before we resort to dimension halving. */
    private const val MIN_JPEG_QUALITY = 40

    /** Lowest dimension we'll downscale to before giving up. */
    private const val MIN_DIMENSION = 64

    /**
     * @return bytes ≤ [MAX_INLINE_PHOTO_BYTES] suitable for the inline
     *         Photo column, or null if the bitmap couldn't be decoded
     *         OR couldn't be compressed small enough.
     */
    fun downscale(bytes: ByteArray): ByteArray? {
        if (bytes.isEmpty()) return null
        if (bytes.size <= MAX_INLINE_PHOTO_BYTES) return bytes

        val original = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        return try {
            tryFit(original)
        } finally {
            if (!original.isRecycled) original.recycle()
        }
    }

    private fun tryFit(original: Bitmap): ByteArray? {
        var width = original.width
        var height = original.height
        var bitmap = original

        while (true) {
            // Slide quality down from 90 to MIN_JPEG_QUALITY in 10-point steps.
            for (quality in 90 downTo MIN_JPEG_QUALITY step 10) {
                val out = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
                val candidate = out.toByteArray()
                if (candidate.size <= MAX_INLINE_PHOTO_BYTES) return candidate
            }
            // Halve dimensions and try again.
            width /= 2
            height /= 2
            if (width < MIN_DIMENSION || height < MIN_DIMENSION) return null
            val scaled = Bitmap.createScaledBitmap(bitmap, width, height, /* filter */ true)
            if (bitmap !== original && !bitmap.isRecycled) bitmap.recycle()
            bitmap = scaled
        }
    }
}
