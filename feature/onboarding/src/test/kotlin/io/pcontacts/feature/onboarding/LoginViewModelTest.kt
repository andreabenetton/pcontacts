// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.feature.onboarding

import io.pcontacts.core.sync.auth.LoginResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {

    @Test fun success_transitions_idle_submitting_success() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val vm = LoginViewModel(
            attemptLogin = { _, _ -> LoginResult.Success(uid = "uid-1") },
            scope = scope,
            workDispatcher = dispatcher
        )
        assertEquals(LoginUiState.Idle, vm.uiState.value)

        vm.login("alice", "pw".toCharArray())
        assertEquals(LoginUiState.Submitting, vm.uiState.value)

        advanceUntilIdle()
        assertEquals(LoginUiState.Success(uid = "uid-1"), vm.uiState.value)
    }

    @Test fun two_factor_required_surfaces_in_state() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val vm = LoginViewModel(
            attemptLogin = { _, _ -> LoginResult.TwoFactorRequired(uid = "uid-2fa") },
            scope = scope,
            workDispatcher = dispatcher
        )
        vm.login("u", "p".toCharArray())
        advanceUntilIdle()
        assertEquals(LoginUiState.TwoFactorRequired(uid = "uid-2fa"), vm.uiState.value)
    }

    @Test fun failure_surfaces_reason_in_state() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val vm = LoginViewModel(
            attemptLogin = { _, _ -> LoginResult.Failed(reason = "auth_failed") },
            scope = scope,
            workDispatcher = dispatcher
        )
        vm.login("u", "p".toCharArray())
        advanceUntilIdle()
        assertEquals(LoginUiState.Failed(reason = "auth_failed"), vm.uiState.value)
    }

    @Test fun second_tap_while_submitting_is_a_noop() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)

        // Block the first attempt until we explicitly release it.
        val gate = CompletableDeferred<LoginResult>()
        var callCount = 0
        val vm = LoginViewModel(
            attemptLogin = { _, _ ->
                callCount += 1
                gate.await()
            },
            scope = scope,
            workDispatcher = dispatcher
        )

        vm.login("u", "p".toCharArray())
        advanceUntilIdle()
        assertEquals(LoginUiState.Submitting, vm.uiState.value)
        assertEquals(1, callCount)

        vm.login("u", "p".toCharArray())   // should be ignored
        advanceUntilIdle()
        assertEquals(LoginUiState.Submitting, vm.uiState.value)
        assertEquals("second tap must not invoke orchestrator a second time", 1, callCount)

        gate.complete(LoginResult.Success(uid = "uid-late"))
        advanceUntilIdle()
        assertEquals(LoginUiState.Success(uid = "uid-late"), vm.uiState.value)
    }

    @Test fun reset_returns_to_idle_and_cancels_pending_job() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val gate = CompletableDeferred<LoginResult>()
        val vm = LoginViewModel(
            attemptLogin = { _, _ -> gate.await() },
            scope = scope,
            workDispatcher = dispatcher
        )
        vm.login("u", "p".toCharArray())
        advanceUntilIdle()
        assertEquals(LoginUiState.Submitting, vm.uiState.value)

        vm.reset()
        assertEquals(LoginUiState.Idle, vm.uiState.value)
    }
}
