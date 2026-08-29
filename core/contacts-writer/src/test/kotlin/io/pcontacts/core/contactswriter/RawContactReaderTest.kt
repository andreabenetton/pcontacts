// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.contactswriter

import android.database.MatrixCursor
import android.provider.ContactsContract.RawContacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class RawContactReaderTest {

    @Test fun parse_empty_cursor_returns_empty_state() {
        val out = RawContactReader.parse(cursor())
        assertTrue(out.rowsBySourceId.isEmpty())
    }

    @Test fun parse_round_trips_id_and_source_id_pairs() {
        val out = RawContactReader.parse(
            cursor(
                row(100L, "ct-1"),
                row(200L, "ct-2"),
                row(300L, "ct-3")
            )
        )
        assertEquals(mapOf("ct-1" to 100L, "ct-2" to 200L, "ct-3" to 300L), out.canonicalIds())
    }

    @Test fun parse_skips_rows_with_null_source_id() {
        // Foreign RawContacts (other apps) often lack SOURCE_ID; we must
        // ignore them, not crash, and they're certainly not "ours".
        val out = RawContactReader.parse(
            cursor(
                row(100L, "ct-1"),
                row(150L, null),
                row(200L, "ct-2")
            )
        )
        assertEquals(mapOf("ct-1" to 100L, "ct-2" to 200L), out.canonicalIds())
    }

    @Test fun parse_retains_every_duplicate_row_for_a_source_id() {
        // The invalid state a duplicate-cleanup app can produce: three
        // rows under our account, same SOURCE_ID. A flat map would
        // silently collapse this; the state object must expose it.
        val out = RawContactReader.parse(
            cursor(
                row(100L, "ct-dup"),
                row(200L, "ct-dup"),
                row(300L, "ct-dup")
            )
        )
        assertEquals(
            listOf(100L, 200L, 300L),
            out.rowsBySourceId["ct-dup"]!!.map { it.rawContactId }
        )
    }

    @Test fun canonical_id_prefers_the_mapped_row_then_lowest_live_id() {
        val out = RawContactReader.parse(
            cursor(
                row(100L, "ct-dup"),
                row(200L, "ct-dup"),
                row(300L, "ct-dup")
            )
        )
        assertEquals(200L, out.canonicalId("ct-dup", preferred = 200L))
        assertEquals(100L, out.canonicalId("ct-dup", preferred = null))
        // A preferred id that is not among the rows falls back to lowest.
        assertEquals(100L, out.canonicalId("ct-dup", preferred = 999L))
        assertEquals(listOf(100L, 300L), out.duplicateIds("ct-dup", canonicalId = 200L))
        assertNull(out.canonicalId("ct-unknown"))
    }

    @Test fun canonical_id_prefers_a_live_row_over_a_lower_id_tombstone() {
        val out = RawContactReader.parse(
            cursor(
                row(100L, "ct-x", deleted = 1),
                row(200L, "ct-x")
            )
        )
        assertEquals(200L, out.canonicalId("ct-x"))
        // A preferred id pointing at the tombstone is ignored — canonical
        // must be a live row when one exists.
        assertEquals(200L, out.canonicalId("ct-x", preferred = 100L))
    }

    @Test fun tombstoned_rows_still_count_as_present() {
        // A locally-deleted contact (DELETED=1, delete not yet pushed to
        // Proton) must read as existing — otherwise the pull engine would
        // resurrect it before the deletion propagates.
        val out = RawContactReader.parse(cursor(row(100L, "ct-del", deleted = 1)))
        assertTrue(out.contains("ct-del"))
        assertEquals(100L, out.canonicalId("ct-del"))
    }

    // --- helpers ---

    private fun cursor(vararg rows: Array<Any?>): MatrixCursor =
        MatrixCursor(arrayOf(RawContacts._ID, RawContacts.SOURCE_ID, RawContacts.DELETED)).apply {
            rows.forEach { addRow(it) }
        }

    private fun row(id: Long, sourceId: String?, deleted: Int = 0): Array<Any?> =
        arrayOf(id, sourceId, deleted)
}
