// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.feature.settings

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LinkedImportListViewModelTest {

    private val rows = listOf(
        LinkedContactRow(1L, "Amy", "Device", inProton = true, newFields = 1),
        LinkedContactRow(2L, "Zoe", "WhatsApp, Signal", inProton = false, newFields = 1)
    )

    @Test fun scans_on_creation_and_filters_rows() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var scans = 0
        val vm = LinkedImportListViewModel(
            scan = {
                scans++
                rows
            },
            scope = TestScope(dispatcher),
            workDispatcher = dispatcher
        )
        assertEquals(LinkedImportListState.Scanning, vm.state.value)
        advanceUntilIdle()
        assertEquals(LinkedImportListState.Ready(rows), vm.state.value)
        assertEquals(1, scans)

        assertEquals(rows, rows.filteredBy(LinkedImportFilter.ALL))
        assertEquals(listOf(rows[1]), rows.filteredBy(LinkedImportFilter.NOT_IN_PROTON))
        assertEquals(listOf(rows[0]), rows.filteredBy(LinkedImportFilter.NEW_DETAILS))

        vm.setFilter(LinkedImportFilter.NEW_DETAILS)
        assertEquals(LinkedImportFilter.NEW_DETAILS, vm.filter.value)

        vm.rescan()
        advanceUntilIdle()
        assertEquals(2, scans)
    }

    @Test fun search_narrows_by_name_or_provider_case_insensitively() {
        assertEquals(listOf(rows[1]), rows.filteredBy(LinkedImportFilter.ALL, "zoe"))
        assertEquals(listOf(rows[1]), rows.filteredBy(LinkedImportFilter.ALL, "signal"))
        assertEquals(emptyList<LinkedContactRow>(), rows.filteredBy(LinkedImportFilter.NEW_DETAILS, "zoe"))
        assertEquals(rows, rows.filteredBy(LinkedImportFilter.ALL, "  "))
    }

    @Test fun bulk_import_reports_progress_then_result_clears_selection_and_rescans() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var scans = 0
        var imported: List<Long>? = null
        val vm = LinkedImportListViewModel(
            scan = {
                scans++
                rows
            },
            importMany = { ids, progress ->
                imported = ids
                ids.forEachIndexed { i, _ -> progress(i + 1) }
                BulkResult(created = 1, enriched = 1, failed = 0)
            },
            scope = TestScope(dispatcher),
            workDispatcher = dispatcher
        )
        advanceUntilIdle()
        vm.toggleSelected(1L)
        vm.selectAll(listOf(1L, 2L))
        assertEquals(setOf(1L, 2L), vm.selected.value)
        vm.toggleSelected(2L)
        vm.selectAll(listOf(2L))

        vm.importSelected()
        assertEquals(BulkImportState.Running(0, 2), vm.bulk.value)
        advanceUntilIdle()
        assertEquals(listOf(1L, 2L), imported)
        assertEquals(BulkImportState.Done(BulkResult(1, 1, 0)), vm.bulk.value)
        assertEquals(emptySet<Long>(), vm.selected.value)
        assertEquals(2, scans)

        vm.dismissBulkResult()
        assertEquals(BulkImportState.Idle, vm.bulk.value)
    }

    @Test fun marking_a_row_imported_unselects_it_and_a_rescan_forgets_it() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val vm = LinkedImportListViewModel(scan = { rows }, scope = TestScope(dispatcher), workDispatcher = dispatcher)
        advanceUntilIdle()
        vm.toggleSelected(1L)
        vm.markImported(1L)
        assertEquals(setOf(1L), vm.imported.value)
        assertEquals(emptySet<Long>(), vm.selected.value)
        vm.rescan()
        advanceUntilIdle()
        assertEquals(emptySet<Long>(), vm.imported.value)
    }

    @Test fun a_failing_scan_surfaces_the_exception_class_only() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val vm = LinkedImportListViewModel(
            scan = { throw IllegalStateException("+39 333 leaked") },
            scope = TestScope(dispatcher),
            workDispatcher = dispatcher
        )
        advanceUntilIdle()
        assertEquals(LinkedImportListState.Failed("IllegalStateException"), vm.state.value)
    }
}
