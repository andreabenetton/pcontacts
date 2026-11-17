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
class SettingsViewModelSignatureTest {

    @Test fun unsigned_contacts_are_loaded_apart_from_unverified_ones() = runTest {
        // Review 2026-11-17, F7: not signed is not a failed signature.
        val dispatcher = StandardTestDispatcher(testScheduler)
        val unsigned = listOf(UnverifiedContactSummary(5L, "ct-5", "Eve", null))
        val vm = SettingsViewModel(
            syncNow = { error("not used") },
            signOut = { error("not used") },
            queryUnsignedContacts = { unsigned },
            scope = TestScope(dispatcher),
            workDispatcher = dispatcher
        )
        advanceUntilIdle()

        assertEquals(unsigned, vm.unsignedContacts.value)
        assertTrue(vm.unverifiedContacts.value.isEmpty())
        vm.showUnsignedContactsDialog()
        assertTrue(vm.unsignedDialogOpen.value)
        vm.dismissUnsignedContactsDialog()
        assertFalse(vm.unsignedDialogOpen.value)
    }
}
