// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.contactswriter

import android.database.MatrixCursor
import android.provider.ContactsContract.RawContacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class DirtyContactReaderTest {

    private val columns = arrayOf(
        RawContacts._ID,
        RawContacts.SOURCE_ID,
        RawContacts.DIRTY,
        RawContacts.DELETED
    )

    @Test fun parse_empty_cursor_returns_empty_list() {
        val cursor = MatrixCursor(columns)
        val out = DirtyContactReader.parse(cursor)
        assertTrue(out.isEmpty())
    }

    @Test fun parse_dirty_contact_returns_isDirty_true() {
        val cursor = MatrixCursor(columns).apply {
            addRow(arrayOf<Any?>(100L, "ct-1", 1, 0))
        }
        val out = DirtyContactReader.parse(cursor)
        assertEquals(1, out.size)
        assertEquals(100L, out[0].rawContactId)
        assertEquals("ct-1", out[0].sourceId)
        assertTrue(out[0].isDirty)
        assertFalse(out[0].isDeleted)
    }

    @Test fun parse_deleted_contact_returns_isDeleted_true() {
        val cursor = MatrixCursor(columns).apply {
            addRow(arrayOf<Any?>(200L, "ct-2", 0, 1))
        }
        val out = DirtyContactReader.parse(cursor)
        assertEquals(1, out.size)
        assertTrue(out[0].isDeleted)
        assertFalse(out[0].isDirty)
    }

    @Test fun parse_dirty_and_deleted_contact_returns_both_true() {
        val cursor = MatrixCursor(columns).apply {
            addRow(arrayOf<Any?>(300L, "ct-3", 1, 1))
        }
        val out = DirtyContactReader.parse(cursor)
        assertTrue(out[0].isDirty)
        assertTrue(out[0].isDeleted)
    }

    @Test fun parse_null_source_id_preserved() {
        val cursor = MatrixCursor(columns).apply {
            addRow(arrayOf<Any?>(400L, null, 1, 0))
        }
        val out = DirtyContactReader.parse(cursor)
        assertEquals(1, out.size)
        assertNull(out[0].sourceId)
    }

    @Test fun parse_multiple_contacts() {
        val cursor = MatrixCursor(columns).apply {
            addRow(arrayOf<Any?>(100L, "ct-1", 1, 0))
            addRow(arrayOf<Any?>(200L, "ct-2", 0, 1))
            addRow(arrayOf<Any?>(300L, null, 1, 0))
        }
        val out = DirtyContactReader.parse(cursor)
        assertEquals(3, out.size)
        assertEquals("ct-1", out[0].sourceId)
        assertEquals("ct-2", out[1].sourceId)
        assertNull(out[2].sourceId)
    }
}
