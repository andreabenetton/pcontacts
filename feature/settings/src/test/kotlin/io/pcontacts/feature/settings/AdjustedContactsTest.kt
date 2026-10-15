// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.feature.settings

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AdjustedContactsTest {

    @Test fun adjusted_contacts_load_and_dismissing_the_last_closes_the_dialog() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val adjusted = AdjustedContact("ct-1", "Alice", ProtonAdjustmentKind.NAME_SHORTENED, rawContactId = 100L)
        val dismissed = mutableListOf<AdjustedContact>()
        val vm = SettingsViewModel(
            syncNow = { error("not used") },
            signOut = { error("not used") },
            queryAdjustedContacts = { if (dismissed.isEmpty()) listOf(adjusted) else emptyList() },
            dismissAdjustment = { dismissed += it },
            scope = TestScope(dispatcher),
            workDispatcher = dispatcher
        )
        advanceUntilIdle()
        assertEquals(listOf(adjusted), vm.adjustedContacts.value)
        vm.showAdjustedContactsDialog()
        assertTrue(vm.adjustedDialogOpen.value)

        vm.dismissAdjusted(adjusted)
        advanceUntilIdle()

        assertEquals(listOf(adjusted), dismissed)
        assertTrue(vm.adjustedContacts.value.isEmpty())
        assertFalse(vm.adjustedDialogOpen.value)
    }

    @Test fun every_adjustment_kind_has_its_own_line() {
        val labels = ProtonAdjustmentKind.entries.map(::adjustmentLabel)
        assertEquals(labels.size, labels.toSet().size)
    }
}
