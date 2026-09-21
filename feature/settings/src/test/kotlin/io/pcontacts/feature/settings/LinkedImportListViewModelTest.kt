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
