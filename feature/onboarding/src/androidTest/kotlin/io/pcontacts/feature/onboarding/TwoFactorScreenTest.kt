// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.feature.onboarding

import androidx.compose.material3.Text
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@Suppress("DEPRECATION")
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
    fun the_code_screen_shows_its_footer() {
        val vm = viewModelIn2faState()
        composeRule.setContent {
            TwoFactorScreen(vm, onSuccess = { _, _ -> }, onCancel = {}, footer = { Text("Not affiliated") })
        }
        composeRule.onNodeWithText("Not affiliated").assertIsDisplayed()
    }

    @Test
    fun the_sixth_digit_sends_the_code_by_itself_once() {
        val sent = mutableListOf<String>()
        val vm = viewModelIn2faState(
            submitTotp = {
                sent += it
                LoginResult.Failed("two_factor_rejected")
            }
        )
        composeRule.setContent {
            TwoFactorScreen(vm, onSuccess = { _, _ -> }, onCancel = {})
        }
        composeRule.onNodeWithText("Code").performTextInput("123456")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Code").performTextInput("654321")
        composeRule.waitForIdle()

        assertEquals("as in Proton's web client, only the first code goes by itself", listOf("123456"), sent)
        composeRule.onNodeWithText("Verify").assertIsEnabled()
    }

    @Test
    fun a_recovery_code_takes_letters_and_waits_for_verify() {
        val sent = mutableListOf<String>()
        val vm = viewModelIn2faState(
            submitTotp = {
                sent += it
                LoginResult.Success("uid-2fa", "testuser")
            }
        )
        composeRule.setContent {
            TwoFactorScreen(vm, onSuccess = { _, _ -> }, onCancel = {})
        }
        composeRule.onNodeWithText("Use a recovery code").performClick()
        composeRule.onNodeWithText("Recovery code").performTextInput("ab12 cd34")
        composeRule.waitForIdle()
        assertTrue(sent.isEmpty())

        composeRule.onNodeWithText("Verify").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("ab12cd34"), sent)
    }

    @Test
    fun submitting_state_disables_input_and_shows_progress() {
        val gate = CompletableDeferred<LoginResult>()
        val vm = viewModelIn2faState(submitTotp = { gate.await() })

        composeRule.setContent {
            TwoFactorScreen(vm, onSuccess = { _, _ -> }, onCancel = {})
        }
        composeRule.onNodeWithText("Code").performTextInput("123456")

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
