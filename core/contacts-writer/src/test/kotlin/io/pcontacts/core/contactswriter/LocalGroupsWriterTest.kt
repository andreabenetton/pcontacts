// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.contactswriter

import android.database.MatrixCursor
import android.provider.ContactsContract.Groups
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class LocalGroupsWriterTest {

    @Test fun parseExisting_returns_empty_for_empty_cursor() {
        val cursor = MatrixCursor(arrayOf(Groups._ID, Groups.SOURCE_ID))
        assertTrue(LocalGroupsWriter.parseExisting(cursor).isEmpty())
    }

    @Test fun parseExisting_round_trips_source_id_to_row_id() {
        val cursor = MatrixCursor(arrayOf(Groups._ID, Groups.SOURCE_ID)).apply {
            addRow(arrayOf<Any>(100L, "label-1"))
            addRow(arrayOf<Any>(200L, "label-2"))
        }
        assertEquals(
            mapOf("label-1" to 100L, "label-2" to 200L),
            LocalGroupsWriter.parseExisting(cursor)
        )
    }

    @Test fun parseExisting_skips_rows_with_null_source_id() {
        val cursor = MatrixCursor(arrayOf(Groups._ID, Groups.SOURCE_ID)).apply {
            addRow(arrayOf<Any?>(100L, "label-1"))
            addRow(arrayOf<Any?>(150L, null))
            addRow(arrayOf<Any?>(200L, "label-2"))
        }
        assertEquals(
            mapOf("label-1" to 100L, "label-2" to 200L),
            LocalGroupsWriter.parseExisting(cursor)
        )
    }
}
