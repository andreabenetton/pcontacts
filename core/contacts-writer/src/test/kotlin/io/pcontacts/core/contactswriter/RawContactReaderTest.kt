// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.contactswriter

import android.database.MatrixCursor
import android.provider.ContactsContract.RawContacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class RawContactReaderTest {

    @Test fun parse_empty_cursor_returns_empty_map() {
        val cursor = MatrixCursor(arrayOf(RawContacts._ID, RawContacts.SOURCE_ID))
        val out = RawContactReader.parse(cursor)
        assertTrue(out.isEmpty())
    }

    @Test fun parse_round_trips_id_and_source_id_pairs() {
        val cursor = MatrixCursor(arrayOf(RawContacts._ID, RawContacts.SOURCE_ID)).apply {
            addRow(arrayOf<Any>(100L, "ct-1"))
            addRow(arrayOf<Any>(200L, "ct-2"))
            addRow(arrayOf<Any>(300L, "ct-3"))
        }
        val out = RawContactReader.parse(cursor)
        assertEquals(mapOf("ct-1" to 100L, "ct-2" to 200L, "ct-3" to 300L), out)
    }

    @Test fun parse_skips_rows_with_null_source_id() {
        // Foreign RawContacts (other apps) often lack SOURCE_ID; we must
        // ignore them, not crash, and they're certainly not "ours".
        val cursor = MatrixCursor(arrayOf(RawContacts._ID, RawContacts.SOURCE_ID)).apply {
            addRow(arrayOf<Any?>(100L, "ct-1"))
            addRow(arrayOf<Any?>(150L, null))
            addRow(arrayOf<Any?>(200L, "ct-2"))
        }
        val out = RawContactReader.parse(cursor)
        assertEquals(mapOf("ct-1" to 100L, "ct-2" to 200L), out)
    }
}
