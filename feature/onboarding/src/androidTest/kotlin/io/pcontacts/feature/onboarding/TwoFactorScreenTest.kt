// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.feature.onboarding

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import io.pcontacts.core.sync.auth.LoginResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TwoFactorScreenTest {

    @get:Rule val composeRule = createComposeRule()

    private fun viewModelIn2faState(
        submitTotp: suspend (String) -> LoginResult = { LoginResult.Success("uid-2fa", "testuser") }
    ): LoginViewModel {
        val vm = LoginViewModel(
            attemptLogin = { _, _ -> LoginResult.TwoFactorRequired("uid-2fa", "testuser") },
            submitTotp = submitTotp,
            workDispatcher = UnconfinedTestDispatcher()
        )
        vm.login("u", "p".toCharArray())
        return vm
    }

    @Test
    fun two_factor_required_state_shows_code_input_and_submit_disabled() {
        val vm = viewModelIn2faState()
        composeRule.setContent {
            TwoFactorScreen(vm, onSuccess = { _, _ -> }, onCancel = {})
        }
        composeRule.onNodeWithText("Code").assertIsDisplayed()
        composeRule.onNodeWithText("Verify").assertIsDisplayed().assertIsNotEnabled()
    }

    @Test
    fun six_digit_code_enables_verify_button() {
        val vm = viewModelIn2faState()
        composeRule.setContent {
            TwoFactorScreen(vm, onSuccess = { _, _ -> }, onCancel = {})
        }
        composeRule.onNodeWithText("Code").performTextInput("123456")
        composeRule.onNodeWithText("Verify").assertIsEnabled()
    }

    @Test
    fun submitting_state_disables_input_and_shows_progress() {
        val gate = CompletableDeferred<LoginResult>()
        val vm = viewModelIn2faState(submitTotp = { gate.await() })

        composeRule.setContent {
            TwoFactorScreen(vm, onSuccess = { _, _ -> }, onCancel = {})
        }
        composeRule.onNodeWithText("Code").performTextInput("123456")
        composeRule.onNodeWithText("Verify").performClick()

        composeRule.waitForIdle()
        composeRule.onNodeWithText("Code").assertIsNotEnabled()
        composeRule.onNodeWithText("Verify").assertIsNotEnabled()
    }

    @Test
    fun failed_state_re_enables_input_and_shows_error() {
        val vm = viewModelIn2faState(
            submitTotp = { LoginResult.Failed("two_factor_rejected") }
        )

        composeRule.setContent {
            TwoFactorScreen(vm, onSuccess = { _, _ -> }, onCancel = {})
        }
        composeRule.onNodeWithText("Code").performTextInput("000000")
        composeRule.onNodeWithText("Verify").performClick()

        composeRule.waitForIdle()
        composeRule.onNodeWithText("Code").assertIsEnabled()
        composeRule.onNode(hasText("Wrong code", substring = true))
            .assertIsDisplayed()
    }

    @Test
    fun cancel_button_calls_on_cancel_and_resets_view_model() {
        val vm = viewModelIn2faState()
        var cancelCalled = false

        composeRule.setContent {
            TwoFactorScreen(
                vm,
                onSuccess = { _, _ -> },
                onCancel = {
                    cancelCalled = true
                    vm.reset()
                }
            )
        }
        composeRule.onNodeWithText("Cancel sign-in").performClick()
        composeRule.waitForIdle()
        assertTrue(cancelCalled)
    }
}
