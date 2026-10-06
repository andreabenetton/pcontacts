// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.feature.onboarding

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import io.pcontacts.core.sync.auth.LoginResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@Suppress("DEPRECATION")
@OptIn(ExperimentalCoroutinesApi::class)
class SecondPasswordScreenTest {

    @get:Rule val composeRule = createComposeRule()

    private fun viewModel(submit: suspend (CharArray) -> LoginResult): LoginViewModel {
        val vm = LoginViewModel(
            attemptLogin = { _, _ -> LoginResult.SecondPasswordRequired("uid-2p", "u") },
            submitTotp = { error("not used") },
            submitSecondPassword = submit,
            workDispatcher = UnconfinedTestDispatcher()
        )
        vm.login("u", "p".toCharArray())
        return vm
    }

    @Test
    fun the_step_explains_two_password_mode_and_starts_on_the_field() {
        val vm = viewModel { error("not yet") }
        composeRule.setContent {
            SecondPasswordScreen(vm, onSuccess = { _, _ -> }, onCancel = {})
        }
        composeRule.onNode(hasText("two-password mode", substring = true)).assertIsDisplayed()
        composeRule.onNodeWithText("Second password").assertIsFocused()
        composeRule.onNodeWithText("Unlock").assertIsNotEnabled()
    }

    @Test
    fun the_typed_password_is_submitted_and_a_success_hands_over() {
        val received = mutableListOf<String>()
        var signedIn: String? = null
        val vm = viewModel {
            received += String(it)
            LoginResult.Success("uid-2p", "u")
        }
        composeRule.setContent {
            SecondPasswordScreen(vm, onSuccess = { uid, _ -> signedIn = uid }, onCancel = {})
        }
        composeRule.onNodeWithText("Second password").performTextInput("mailbox")
        composeRule.onNodeWithText("Unlock").assertIsEnabled().performClick()
        composeRule.waitForIdle()

        assertEquals(listOf("mailbox"), received)
        assertEquals("uid-2p", signedIn)
    }

    @Test
    fun a_wrong_second_password_says_so_in_protons_words() {
        val vm = viewModel { LoginResult.Failed("second_password_rejected", "uid-2p", "u") }
        composeRule.setContent {
            SecondPasswordScreen(vm, onSuccess = { _, _ -> }, onCancel = {})
        }
        composeRule.onNodeWithText("Second password").performTextInput("wrong")
        composeRule.onNodeWithText("Unlock").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Incorrect second password. Please try again.").assertIsDisplayed()
    }
}
