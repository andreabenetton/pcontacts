// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.feature.onboarding

import io.pcontacts.core.sync.auth.LoginResult
import kotlinx.coroutines.CompletableDeferred
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
class LoginViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    private val unusedSubmitTotp: suspend (String) -> LoginResult =
        { error("submitTotp should not be called in this test") }

    @Test fun success_transitions_idle_submitting_success() = runTest {
        val vm = LoginViewModel(
            attemptLogin = { _, _ -> LoginResult.Success(uid = "uid-1", username = "alice") },
            submitTotp = unusedSubmitTotp,
            workDispatcher = testDispatcher
        )
        assertEquals(LoginUiState.Idle, vm.uiState.value)

        vm.login("alice", "pw".toCharArray())
        assertEquals(LoginUiState.Submitting, vm.uiState.value)

        advanceUntilIdle()
        assertEquals(LoginUiState.Success(uid = "uid-1", username = "alice"), vm.uiState.value)
    }

    @Test fun two_factor_required_surfaces_in_state() = runTest {
        val vm = LoginViewModel(
            attemptLogin = { _, _ -> LoginResult.TwoFactorRequired(uid = "uid-2fa", username = "u") },
            submitTotp = unusedSubmitTotp,
            workDispatcher = testDispatcher
        )
        vm.login("u", "p".toCharArray())
        advanceUntilIdle()
        assertEquals(LoginUiState.TwoFactorRequired(uid = "uid-2fa", username = "u"), vm.uiState.value)
    }

    @Test fun human_verification_required_surfaces_url_in_state() = runTest {
        val vm = LoginViewModel(
            attemptLogin = { _, _ ->
                LoginResult.HumanVerificationRequired(
                    verificationUrl = "https://verify.proton.me/?token=t&methods=captcha"
                )
            },
            submitTotp = unusedSubmitTotp,
            workDispatcher = testDispatcher
        )
        vm.login("u", "p".toCharArray())
        advanceUntilIdle()
        assertEquals(
            LoginUiState.HumanVerificationRequired("https://verify.proton.me/?token=t&methods=captcha"),
            vm.uiState.value
        )
    }

    @Test fun retry_after_verification_reruns_attempt_login_with_stored_credentials() = runTest {
        var calls = 0
        var lastUsername: String? = null
        var lastPasswordChars: CharArray? = null
        val vm = LoginViewModel(
            attemptLogin = { u, p ->
                calls += 1
                lastUsername = u
                lastPasswordChars = p.copyOf()
                if (calls == 1) {
                    LoginResult.HumanVerificationRequired(
                        verificationUrl = "https://verify.proton.me/?token=t&methods=captcha"
                    )
                } else {
                    LoginResult.Success(uid = "uid-after-hv", username = u)
                }
            },
            submitTotp = unusedSubmitTotp,
            workDispatcher = testDispatcher
        )

        vm.login("alice", "p4ssw0rd".toCharArray())
        advanceUntilIdle()
        assertEquals(
            LoginUiState.HumanVerificationRequired("https://verify.proton.me/?token=t&methods=captcha"),
            vm.uiState.value
        )

        vm.retryAfterVerification()
        advanceUntilIdle()
        assertEquals(LoginUiState.Success(uid = "uid-after-hv", username = "alice"), vm.uiState.value)
        assertEquals(2, calls)
        assertEquals("alice", lastUsername)
        // Each attempt receives its own CharArray copy (the orchestrator
        // zeroes the one it receives in finally).
        assertEquals("p4ssw0rd".toCharArray().toList(), lastPasswordChars?.toList())
    }

    @Test fun retry_after_verification_is_a_noop_when_not_in_human_verification_state() = runTest {
        var attemptCount = 0
        val vm = LoginViewModel(
            attemptLogin = { _, _ ->
                attemptCount += 1
                LoginResult.Failed(reason = "auth_failed")
            },
            submitTotp = unusedSubmitTotp,
            workDispatcher = testDispatcher
        )

        vm.login("u", "p".toCharArray())
        advanceUntilIdle()
        assertEquals(1, attemptCount)

        // After Failed, retry should not fire — captcha never happened.
        vm.retryAfterVerification()
        advanceUntilIdle()
        assertEquals("retryAfterVerification must not re-run from Failed state", 1, attemptCount)
    }

    @Test fun failure_surfaces_reason_in_state() = runTest {
        val vm = LoginViewModel(
            attemptLogin = { _, _ -> LoginResult.Failed(reason = "auth_failed") },
            submitTotp = unusedSubmitTotp,
            workDispatcher = testDispatcher
        )
        vm.login("u", "p".toCharArray())
        advanceUntilIdle()
        assertEquals(LoginUiState.Failed(reason = "auth_failed"), vm.uiState.value)
    }

    @Test fun second_tap_while_submitting_is_a_noop() = runTest {
        val gate = CompletableDeferred<LoginResult>()
        var callCount = 0
        val vm = LoginViewModel(
            attemptLogin = { _, _ ->
                callCount += 1
                gate.await()
            },
            submitTotp = unusedSubmitTotp,
            workDispatcher = testDispatcher
        )

        vm.login("u", "p".toCharArray())
        advanceUntilIdle()
        assertEquals(LoginUiState.Submitting, vm.uiState.value)
        assertEquals(1, callCount)

        vm.login("u", "p".toCharArray())   // should be ignored
        advanceUntilIdle()
        assertEquals(LoginUiState.Submitting, vm.uiState.value)
        assertEquals("second tap must not invoke orchestrator a second time", 1, callCount)

        gate.complete(LoginResult.Success(uid = "uid-late", username = "u"))
        advanceUntilIdle()
        assertEquals(LoginUiState.Success(uid = "uid-late", username = "u"), vm.uiState.value)
    }

    @Test fun reset_returns_to_idle_and_cancels_pending_job() = runTest {
        val gate = CompletableDeferred<LoginResult>()
        val vm = LoginViewModel(
            attemptLogin = { _, _ -> gate.await() },
            submitTotp = unusedSubmitTotp,
            workDispatcher = testDispatcher
        )
        vm.login("u", "p".toCharArray())
        advanceUntilIdle()
        assertEquals(LoginUiState.Submitting, vm.uiState.value)

        vm.reset()
        assertEquals(LoginUiState.Idle, vm.uiState.value)
    }

    // --- 2FA / TOTP ---

    @Test fun submitTwoFactor_success_transitions_required_submitting_success() = runTest {
        var capturedCode: String? = null
        val vm = LoginViewModel(
            attemptLogin = { _, _ -> LoginResult.TwoFactorRequired(uid = "uid-2fa", username = "u") },
            submitTotp = { code ->
                capturedCode = code
                LoginResult.Success(uid = "uid-2fa", username = "u")
            },
            workDispatcher = testDispatcher
        )

        vm.login("u", "p".toCharArray())
        advanceUntilIdle()
        assertEquals(LoginUiState.TwoFactorRequired(uid = "uid-2fa", username = "u"), vm.uiState.value)

        vm.submitTwoFactor("123456")
        assertEquals(LoginUiState.TwoFactorSubmitting(uid = "uid-2fa", username = "u"), vm.uiState.value)

        advanceUntilIdle()
        assertEquals(LoginUiState.Success(uid = "uid-2fa", username = "u"), vm.uiState.value)
        assertEquals("123456", capturedCode)
    }

    @Test fun submitTwoFactor_failure_surfaces_TwoFactorFailed_preserving_uid() = runTest {
        val vm = LoginViewModel(
            attemptLogin = { _, _ -> LoginResult.TwoFactorRequired(uid = "uid-fail", username = "u") },
            submitTotp = { _ -> LoginResult.Failed(reason = "two_factor_rejected") },
            workDispatcher = testDispatcher
        )
        vm.login("u", "p".toCharArray())
        advanceUntilIdle()
        vm.submitTwoFactor("000000")
        advanceUntilIdle()

        assertEquals(
            LoginUiState.TwoFactorFailed(uid = "uid-fail", username = "u", reason = "two_factor_rejected"),
            vm.uiState.value
        )
    }

    @Test fun submitTwoFactor_from_failed_state_retries() = runTest {
        var attempts = 0
        val vm = LoginViewModel(
            attemptLogin = { _, _ -> LoginResult.TwoFactorRequired(uid = "uid-retry", username = "u") },
            submitTotp = { _ ->
                attempts += 1
                if (attempts == 1) LoginResult.Failed("two_factor_rejected")
                else LoginResult.Success(uid = "uid-retry", username = "u")
            },
            workDispatcher = testDispatcher
        )
        vm.login("u", "p".toCharArray())
        advanceUntilIdle()

        vm.submitTwoFactor("111111")
        advanceUntilIdle()
        assertEquals(
            LoginUiState.TwoFactorFailed(uid = "uid-retry", username = "u", reason = "two_factor_rejected"),
            vm.uiState.value
        )

        vm.submitTwoFactor("222222")
        advanceUntilIdle()
        assertEquals(LoginUiState.Success(uid = "uid-retry", username = "u"), vm.uiState.value)
        assertEquals(2, attempts)
    }

    @Test fun submitTwoFactor_ignored_when_not_in_2fa_state() = runTest {
        var totpCalled = false
        val vm = LoginViewModel(
            attemptLogin = { _, _ -> LoginResult.Success(uid = "no-2fa-here", username = "u") },
            submitTotp = { _ ->
                totpCalled = true
                LoginResult.Success(uid = "x", username = "u")
            },
            workDispatcher = testDispatcher
        )

        vm.submitTwoFactor("123456")
        advanceUntilIdle()
        assertEquals(LoginUiState.Idle, vm.uiState.value)
        assertEquals(false, totpCalled)
    }
}
