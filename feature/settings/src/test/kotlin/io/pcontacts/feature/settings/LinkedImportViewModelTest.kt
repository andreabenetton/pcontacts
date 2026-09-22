// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.feature.settings

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LinkedImportViewModelTest {

    private val preview = LinkedImportPreview(
        contactName = "Evelino",
        candidates = listOf(
            LinkedImportCandidate("phone:+393331234567", LinkedFieldKind.PHONE, "+39 333 1234567", "WhatsApp"),
            LinkedImportCandidate("note:met in Milan", LinkedFieldKind.NOTE, "met in Milan", null)
        )
    )

    @Test fun start_preselects_every_candidate_and_confirm_imports_the_selection() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var imported: List<String>? = null
        val vm = LinkedImportViewModel(
            loadPreview = { preview },
            importCandidates = {
                imported = it
                true
            },
            scope = TestScope(dispatcher),
            workDispatcher = dispatcher
        )
        vm.start(contactId = 7L)
        assertEquals(LinkedImportState.Loading, vm.state.value)
        advanceUntilIdle()
        assertEquals(
            LinkedImportState.Review(preview, setOf("phone:+393331234567", "note:met in Milan")),
            vm.state.value
        )

        vm.toggle("note:met in Milan")
        assertEquals(setOf("phone:+393331234567"), (vm.state.value as LinkedImportState.Review).selected)

        vm.confirm()
        assertEquals(LinkedImportState.Importing, vm.state.value)
        advanceUntilIdle()
        assertEquals(listOf("phone:+393331234567"), imported)
        assertEquals(LinkedImportState.Imported(1, created = false, contactId = 7L), vm.state.value)
    }

    @Test fun confirm_with_nothing_selected_is_a_noop() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var imported: List<String>? = null
        val vm = LinkedImportViewModel(
            loadPreview = { preview },
            importCandidates = {
                imported = it
                true
            },
            scope = TestScope(dispatcher),
            workDispatcher = dispatcher
        )
        vm.start(7L)
        advanceUntilIdle()
        vm.toggle("phone:+393331234567")
        vm.toggle("note:met in Milan")
        vm.confirm()
        advanceUntilIdle()
        assertNull(imported)
        assertTrue(vm.state.value is LinkedImportState.Review)
    }

    @Test fun null_preview_means_the_contact_is_gone() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val vm = LinkedImportViewModel(
            loadPreview = { null },
            importCandidates = { error("not used") },
            scope = TestScope(dispatcher),
            workDispatcher = dispatcher
        )
        vm.start(7L)
        advanceUntilIdle()
        assertEquals(LinkedImportState.NotFound, vm.state.value)
    }

    @Test fun creating_a_contact_needs_a_reachable_field_and_reports_created() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var imported: List<String>? = null
        val vm = LinkedImportViewModel(
            loadPreview = { preview.copy(createsNewContact = true) },
            importCandidates = {
                imported = it
                true
            },
            scope = TestScope(dispatcher),
            workDispatcher = dispatcher
        )
        vm.start(7L)
        advanceUntilIdle()

        // Only the note selected: not enough to make a contact.
        vm.toggle("phone:+393331234567")
        val noteOnly = vm.state.value as LinkedImportState.Review
        assertEquals(setOf("note:met in Milan"), noteOnly.selected)
        assertTrue(!noteOnly.canConfirm)
        vm.confirm()
        advanceUntilIdle()
        assertNull(imported)

        vm.toggle("phone:+393331234567")
        assertTrue((vm.state.value as LinkedImportState.Review).canConfirm)
        vm.confirm()
        advanceUntilIdle()
        assertEquals(listOf("note:met in Milan", "phone:+393331234567"), imported)
        assertEquals(LinkedImportState.Imported(2, created = true, contactId = 7L), vm.state.value)
    }

    @Test fun failures_surface_the_exception_class_only() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val vm = LinkedImportViewModel(
            loadPreview = { preview },
            importCandidates = { throw IllegalStateException("+39 333 1234567 leaked") },
            scope = TestScope(dispatcher),
            workDispatcher = dispatcher
        )
        vm.start(7L)
        advanceUntilIdle()
        vm.confirm()
        advanceUntilIdle()
        assertEquals(LinkedImportState.Failed("IllegalStateException"), vm.state.value)

        vm.dismiss()
        assertEquals(LinkedImportState.Hidden, vm.state.value)
    }

    @Test fun a_contact_that_changed_before_confirm_reloads_the_preview_for_another_look() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fresh = preview.copy(candidates = preview.candidates.take(1))
        var loads = 0
        val vm = LinkedImportViewModel(
            loadPreview = { if (loads++ == 0) preview else fresh },
            importCandidates = { false },
            scope = TestScope(dispatcher),
            workDispatcher = dispatcher
        )
        vm.start(contactId = 7L)
        advanceUntilIdle()

        vm.confirm()
        advanceUntilIdle()

        assertEquals(LinkedImportState.Review(fresh, setOf("phone:+393331234567"), changed = true), vm.state.value)
        assertEquals(2, loads)
    }
}
