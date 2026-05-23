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

    private val unusedSubmitTotp: suspend (String) -> LoginResult =
        { error("submitTotp should not be called in this test") }

    @Test fun success_transitions_idle_submitting_success() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val vm = LoginViewModel(
            attemptLogin = { _, _ -> LoginResult.Success(uid = "uid-1") },
            submitTotp = unusedSubmitTotp,
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
            submitTotp = unusedSubmitTotp,
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
            submitTotp = unusedSubmitTotp,
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
            submitTotp = unusedSubmitTotp,
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
            submitTotp = unusedSubmitTotp,
            scope = scope,
            workDispatcher = dispatcher
        )
        vm.login("u", "p".toCharArray())
        advanceUntilIdle()
        assertEquals(LoginUiState.Submitting, vm.uiState.value)

        vm.reset()
        assertEquals(LoginUiState.Idle, vm.uiState.value)
    }

    // --- 2FA / TOTP ---

    @Test fun submitTwoFactor_success_transitions_required_submitting_success() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        var capturedCode: String? = null
        val vm = LoginViewModel(
            attemptLogin = { _, _ -> LoginResult.TwoFactorRequired(uid = "uid-2fa") },
            submitTotp = { code ->
                capturedCode = code
                LoginResult.Success(uid = "uid-2fa")
            },
            scope = scope,
            workDispatcher = dispatcher
        )

        // Drive the state machine into TwoFactorRequired first.
        vm.login("u", "p".toCharArray())
        advanceUntilIdle()
        assertEquals(LoginUiState.TwoFactorRequired(uid = "uid-2fa"), vm.uiState.value)

        vm.submitTwoFactor("123456")
        assertEquals(LoginUiState.TwoFactorSubmitting(uid = "uid-2fa"), vm.uiState.value)

        advanceUntilIdle()
        assertEquals(LoginUiState.Success(uid = "uid-2fa"), vm.uiState.value)
        assertEquals("123456", capturedCode)
    }

    @Test fun submitTwoFactor_failure_surfaces_TwoFactorFailed_preserving_uid() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val vm = LoginViewModel(
            attemptLogin = { _, _ -> LoginResult.TwoFactorRequired(uid = "uid-fail") },
            submitTotp = { _ -> LoginResult.Failed(reason = "two_factor_rejected") },
            scope = scope,
            workDispatcher = dispatcher
        )
        vm.login("u", "p".toCharArray()); advanceUntilIdle()
        vm.submitTwoFactor("000000"); advanceUntilIdle()

        assertEquals(
            LoginUiState.TwoFactorFailed(uid = "uid-fail", reason = "two_factor_rejected"),
            vm.uiState.value
        )
    }

    @Test fun submitTwoFactor_from_failed_state_retries() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        var attempts = 0
        val vm = LoginViewModel(
            attemptLogin = { _, _ -> LoginResult.TwoFactorRequired(uid = "uid-retry") },
            submitTotp = { _ ->
                attempts += 1
                if (attempts == 1) LoginResult.Failed("two_factor_rejected")
                else LoginResult.Success(uid = "uid-retry")
            },
            scope = scope,
            workDispatcher = dispatcher
        )
        vm.login("u", "p".toCharArray()); advanceUntilIdle()

        vm.submitTwoFactor("111111"); advanceUntilIdle()
        assertEquals(
            LoginUiState.TwoFactorFailed(uid = "uid-retry", reason = "two_factor_rejected"),
            vm.uiState.value
        )

        vm.submitTwoFactor("222222"); advanceUntilIdle()
        assertEquals(LoginUiState.Success(uid = "uid-retry"), vm.uiState.value)
        assertEquals(2, attempts)
    }

    @Test fun submitTwoFactor_ignored_when_not_in_2fa_state() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        var totpCalled = false
        val vm = LoginViewModel(
            attemptLogin = { _, _ -> LoginResult.Success(uid = "no-2fa-here") },
            submitTotp = { _ -> totpCalled = true; LoginResult.Success(uid = "x") },
            scope = scope,
            workDispatcher = dispatcher
        )

        // From Idle the call must be a no-op.
        vm.submitTwoFactor("123456"); advanceUntilIdle()
        assertEquals(LoginUiState.Idle, vm.uiState.value)
        assertEquals(false, totpCalled)
    }
}
