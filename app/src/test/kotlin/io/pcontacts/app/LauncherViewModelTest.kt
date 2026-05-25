// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.app

import io.pcontacts.core.sync.contacts.LauncherStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LauncherViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test fun initial_state_is_loading() {
        val vm = LauncherViewModel(
            hasAccount = { true },
            loadStatus = { LauncherStatus(0, 0, null) },
            workDispatcher = testDispatcher
        )
        assertEquals(LauncherUiState.Loading, vm.uiState.value)
    }

    @Test fun refresh_emits_no_account_when_account_absent() = runTest {
        val vm = LauncherViewModel(
            hasAccount = { false },
            loadStatus = { error("should not be called") },
            workDispatcher = testDispatcher
        )
        vm.refresh()
        advanceUntilIdle()
        assertEquals(LauncherUiState.NoAccount, vm.uiState.value)
    }

    @Test fun refresh_emits_signed_in_when_account_present() = runTest {
        val status = LauncherStatus(
            totalContacts = 42,
            unverifiedContacts = 3,
            lastSyncedAtMillis = 1_700_000_000L
        )
        val vm = LauncherViewModel(
            hasAccount = { true },
            loadStatus = { status },
            workDispatcher = testDispatcher
        )
        vm.refresh()
        advanceUntilIdle()
        assertEquals(LauncherUiState.SignedIn(status), vm.uiState.value)
    }

    @Test fun refresh_emits_signed_in_with_null_last_sync() = runTest {
        val status = LauncherStatus(
            totalContacts = 0,
            unverifiedContacts = 0,
            lastSyncedAtMillis = null
        )
        val vm = LauncherViewModel(
            hasAccount = { true },
            loadStatus = { status },
            workDispatcher = testDispatcher
        )
        vm.refresh()
        advanceUntilIdle()
        assertEquals(LauncherUiState.SignedIn(status), vm.uiState.value)
    }

    @Test fun refresh_updates_state_on_subsequent_calls() = runTest {
        var callCount = 0
        val vm = LauncherViewModel(
            hasAccount = { true },
            loadStatus = {
                callCount++
                LauncherStatus(
                    totalContacts = callCount * 10,
                    unverifiedContacts = 0,
                    lastSyncedAtMillis = null
                )
            },
            workDispatcher = testDispatcher
        )

        vm.refresh()
        advanceUntilIdle()
        val first = vm.uiState.value as LauncherUiState.SignedIn
        assertEquals(10, first.status.totalContacts)

        vm.refresh()
        advanceUntilIdle()
        val second = vm.uiState.value as LauncherUiState.SignedIn
        assertEquals(20, second.status.totalContacts)
    }
}
