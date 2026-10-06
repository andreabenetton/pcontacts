// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.feature.onboarding

import androidx.lifecycle.ViewModelStore
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

    // --- 9001 during the two-factor phase ---

    private fun twoFactorVm(submitTotp: suspend (String) -> LoginResult, attempts: MutableList<Int> = mutableListOf()) =
        LoginViewModel(
            attemptLogin = { _, _ ->
                attempts += 1
                LoginResult.TwoFactorRequired(uid = "uid-2fa", username = "u")
            },
            submitTotp = submitTotp,
            workDispatcher = testDispatcher
        )

    @Test fun human_verification_during_two_factor_surfaces_the_two_factor_state_and_keeps_the_uid() = runTest {
        val url = "https://verify.proton.me/?token=t"
        val vm = twoFactorVm({ LoginResult.HumanVerificationRequired(url, "uid-2fa", "u") })
        vm.login("u", "p".toCharArray())
        advanceUntilIdle()

        vm.submitTwoFactor("123456")
        advanceUntilIdle()

        assertEquals(LoginUiState.TwoFactorHumanVerificationRequired("uid-2fa", "u", url), vm.uiState.value)
    }

    @Test fun retry_after_verification_in_the_two_factor_phase_returns_to_the_code_screen_without_a_request() = runTest {
        val attempts = mutableListOf<Int>()
        var totpCalls = 0
        val vm = twoFactorVm(
            submitTotp = {
                totpCalls += 1
                LoginResult.HumanVerificationRequired("u", "uid-2fa", "u")
            },
            attempts = attempts
        )
        vm.login("u", "p".toCharArray())
        advanceUntilIdle()
        vm.submitTwoFactor("123456")
        advanceUntilIdle()

        vm.retryAfterVerification()
        advanceUntilIdle()

        assertEquals(LoginUiState.TwoFactorRequired("uid-2fa", "u"), vm.uiState.value)
        assertEquals("no /auth re-run in the 2FA phase", 1, attempts.size)
        assertEquals(1, totpCalls)
    }

    @Test fun fresh_code_after_verification_succeeds_on_the_same_session() = runTest {
        val codes = mutableListOf<String>()
        val vm = twoFactorVm({ code ->
            codes += code
            if (codes.size == 1) {
                LoginResult.HumanVerificationRequired("u", "uid-2fa", "u")
            } else {
                LoginResult.Success("uid-2fa", "u")
            }
        })
        vm.login("u", "p".toCharArray())
        advanceUntilIdle()
        vm.submitTwoFactor("111111")
        advanceUntilIdle()
        vm.retryAfterVerification()

        vm.submitTwoFactor("222222")
        advanceUntilIdle()

        assertEquals(LoginUiState.Success("uid-2fa", "u"), vm.uiState.value)
        assertEquals(listOf("111111", "222222"), codes)
    }

    @Test fun a_second_verification_demand_in_the_two_factor_phase_fails_closed() = runTest {
        val vm = twoFactorVm({ LoginResult.HumanVerificationRequired("u", "uid-2fa", "u") })
        vm.login("u", "p".toCharArray())
        advanceUntilIdle()
        vm.submitTwoFactor("111111")
        advanceUntilIdle()
        vm.retryAfterVerification()

        vm.submitTwoFactor("222222")
        advanceUntilIdle()

        assertEquals(LoginUiState.TwoFactorFailed("uid-2fa", "u", "verification_rejected"), vm.uiState.value)
    }

    @Test fun clearing_the_view_model_aborts_the_login_like_an_explicit_cancel() = runTest {
        var aborted = 0
        val vm = keyDerivationVm(retry = { LoginResult.Failed("unused") }, abort = { aborted++ })
        vm.login("u", "p".toCharArray())
        advanceUntilIdle()

        // The screen is gone for good (system back): the store clears the model.
        ViewModelStore().apply {
            put("login", vm)
            clear()
        }

        assertEquals(1, aborted)
    }

    private fun keyDerivationVm(
        retry: suspend () -> LoginResult,
        abort: () -> Unit = {}
    ) = LoginViewModel(
        attemptLogin = { _, _ -> LoginResult.TwoFactorRequired("uid-kd", "u") },
        submitTotp = {
            LoginResult.HumanVerificationRequired("url", "uid-kd", "u", stage = LoginResult.HvStage.KEY_DERIVATION)
        },
        retryKeyDerivation = retry,
        abortLogin = abort,
        workDispatcher = testDispatcher
    )

    @Test fun a_9001_after_the_accepted_code_surfaces_the_key_derivation_state() = runTest {
        val vm = keyDerivationVm(retry = { error("not yet") })
        vm.login("u", "p".toCharArray())
        advanceUntilIdle()

        vm.submitTwoFactor("123456")
        advanceUntilIdle()

        assertEquals(LoginUiState.KeyDerivationHumanVerificationRequired("uid-kd", "u", "url"), vm.uiState.value)
    }

    @Test fun retry_after_verification_in_the_key_derivation_stage_resumes_without_a_new_code() = runTest {
        var retries = 0
        val vm = keyDerivationVm(
            retry = {
                retries += 1
                LoginResult.Success("uid-kd", "u")
            }
        )
        vm.login("u", "p".toCharArray())
        advanceUntilIdle()
        vm.submitTwoFactor("123456")
        advanceUntilIdle()

        vm.retryAfterVerification()
        assertEquals(LoginUiState.TwoFactorSubmitting("uid-kd", "u"), vm.uiState.value)
        advanceUntilIdle()

        assertEquals(LoginUiState.Success("uid-kd", "u"), vm.uiState.value)
        assertEquals(1, retries)
    }

    @Test fun a_rejected_key_derivation_retry_lands_on_the_code_screen_as_a_failure() = runTest {
        val vm = keyDerivationVm(retry = { LoginResult.Failed("verification_rejected", "uid-kd", "u") })
        vm.login("u", "p".toCharArray())
        advanceUntilIdle()
        vm.submitTwoFactor("123456")
        advanceUntilIdle()

        vm.retryAfterVerification()
        advanceUntilIdle()

        assertEquals(LoginUiState.TwoFactorFailed("uid-kd", "u", "verification_rejected"), vm.uiState.value)
    }

    @Test fun reset_tells_the_orchestrator_to_abort() = runTest {
        var aborted = 0
        val vm = keyDerivationVm(retry = { error("unused") }, abort = { aborted += 1 })
        vm.login("u", "p".toCharArray())
        advanceUntilIdle()

        vm.reset()

        assertEquals(1, aborted)
        assertEquals(LoginUiState.Idle, vm.uiState.value)
    }

    @Test fun reset_from_the_two_factor_verification_state_returns_to_idle_and_clears_the_flag() = runTest {
        val vm = twoFactorVm({ LoginResult.HumanVerificationRequired("u", "uid-2fa", "u") })
        vm.login("u", "p".toCharArray())
        advanceUntilIdle()
        vm.submitTwoFactor("111111")
        advanceUntilIdle()

        vm.reset()
        assertEquals(LoginUiState.Idle, vm.uiState.value)

        // A new sign-in starts over: the next 9001 during 2FA opens the WebView again.
        vm.login("u", "p".toCharArray())
        advanceUntilIdle()
        vm.submitTwoFactor("111111")
        advanceUntilIdle()
        assertEquals(LoginUiState.TwoFactorHumanVerificationRequired("uid-2fa", "u", "u"), vm.uiState.value)
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

    // --- Two-password mode (issue #65) ---

    private fun secondPasswordVm(
        attempt: LoginResult = LoginResult.SecondPasswordRequired("uid-2p", "u"),
        submitSecondPassword: suspend (CharArray) -> LoginResult,
        retry: suspend () -> LoginResult = { error("not used") }
    ) = LoginViewModel(
        attemptLogin = { _, _ -> attempt },
        submitTotp = { LoginResult.SecondPasswordRequired("uid-2p", "u") },
        retryKeyDerivation = retry,
        submitSecondPassword = submitSecondPassword,
        workDispatcher = testDispatcher
    )

    @Test fun two_password_mode_asks_for_the_second_password_after_the_login_one() = runTest {
        val vm = secondPasswordVm(submitSecondPassword = { error("not yet") })
        vm.login("u", "p".toCharArray())
        advanceUntilIdle()

        assertEquals(LoginUiState.SecondPasswordRequired("uid-2p", "u"), vm.uiState.value)
    }

    @Test fun two_password_mode_with_two_factor_asks_for_it_after_the_code() = runTest {
        val vm = secondPasswordVm(
            attempt = LoginResult.TwoFactorRequired("uid-2p", "u"),
            submitSecondPassword = { error("not yet") }
        )
        vm.login("u", "p".toCharArray())
        advanceUntilIdle()
        vm.submitTwoFactor("123456")
        advanceUntilIdle()

        assertEquals(LoginUiState.SecondPasswordRequired("uid-2p", "u"), vm.uiState.value)
    }

    @Test fun the_second_password_finishes_the_sign_in() = runTest {
        val received = mutableListOf<String>()
        val vm = secondPasswordVm(
            submitSecondPassword = {
                received += String(it)
                LoginResult.Success("uid-2p", "u")
            }
        )
        vm.login("u", "p".toCharArray())
        advanceUntilIdle()

        vm.submitSecondPassword("second".toCharArray())
        assertEquals(LoginUiState.SecondPasswordSubmitting("uid-2p", "u"), vm.uiState.value)
        advanceUntilIdle()

        assertEquals(listOf("second"), received)
        assertEquals(LoginUiState.Success("uid-2p", "u"), vm.uiState.value)
    }

    @Test fun a_wrong_second_password_stays_on_the_step_and_can_be_retried() = runTest {
        var tries = 0
        val vm = secondPasswordVm(
            submitSecondPassword = {
                tries++
                if (tries == 1) {
                    LoginResult.Failed("second_password_rejected", "uid-2p", "u")
                } else {
                    LoginResult.Success("uid-2p", "u")
                }
            }
        )
        vm.login("u", "p".toCharArray())
        advanceUntilIdle()

        vm.submitSecondPassword("wrong".toCharArray())
        advanceUntilIdle()
        assertEquals(LoginUiState.SecondPasswordFailed("uid-2p", "u", "second_password_rejected"), vm.uiState.value)

        vm.submitSecondPassword("right".toCharArray())
        advanceUntilIdle()
        assertEquals(LoginUiState.Success("uid-2p", "u"), vm.uiState.value)
    }

    @Test fun a_captcha_on_the_second_password_step_resumes_on_that_step() = runTest {
        val vm = secondPasswordVm(
            submitSecondPassword = {
                LoginResult.HumanVerificationRequired("url", "uid-2p", "u", stage = LoginResult.HvStage.KEY_DERIVATION)
            },
            retry = { LoginResult.Failed("second_password_rejected", "uid-2p", "u") }
        )
        vm.login("u", "p".toCharArray())
        advanceUntilIdle()
        vm.submitSecondPassword("second".toCharArray())
        advanceUntilIdle()
        assertEquals(
            LoginUiState.KeyDerivationHumanVerificationRequired("uid-2p", "u", "url"),
            vm.uiState.value
        )

        vm.retryAfterVerification()
        advanceUntilIdle()

        assertEquals(LoginUiState.SecondPasswordFailed("uid-2p", "u", "second_password_rejected"), vm.uiState.value)
    }
}
