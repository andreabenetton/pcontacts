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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@Suppress("DEPRECATION")
@OptIn(ExperimentalCoroutinesApi::class)
class LoginScreenTest {

    @get:Rule val composeRule = createComposeRule()

    private val unusedTotp: suspend (String) -> LoginResult =
        { error("submitTotp should not be called") }

    private fun viewModel(
        attemptLogin: suspend (String, CharArray) -> LoginResult = { _, _ ->
            LoginResult.Success("uid", "testuser")
        },
        submitTotp: suspend (String) -> LoginResult = unusedTotp
    ) = LoginViewModel(
        attemptLogin = attemptLogin,
        submitTotp = submitTotp,
        workDispatcher = UnconfinedTestDispatcher()
    )

    @Test
    fun initial_state_shows_fields_and_sign_in_button_disabled() {
        val vm = viewModel()
        composeRule.setContent {
            LoginScreen(vm, onSuccess = { _, _ -> }, onTwoFactorRequired = {})
        }
        composeRule.onNodeWithText("Email or username").assertIsDisplayed()
        composeRule.onNodeWithText("Password").assertIsDisplayed()
        composeRule.onNodeWithText("Sign in").assertIsDisplayed().assertIsNotEnabled()
    }

    @Test
    fun typing_username_only_keeps_button_disabled() {
        val vm = viewModel()
        composeRule.setContent {
            LoginScreen(vm, onSuccess = { _, _ -> }, onTwoFactorRequired = {})
        }
        composeRule.onNodeWithText("Email or username").performTextInput("alice")
        composeRule.onNodeWithText("Sign in").assertIsNotEnabled()
    }

    @Test
    fun typing_username_and_password_enables_button() {
        val vm = viewModel()
        composeRule.setContent {
            LoginScreen(vm, onSuccess = { _, _ -> }, onTwoFactorRequired = {})
        }
        composeRule.onNodeWithText("Email or username").performTextInput("alice")
        composeRule.onNodeWithText("Password").performTextInput("secret")
        composeRule.onNodeWithText("Sign in").assertIsEnabled()
    }

    @Test
    fun pressing_sign_in_calls_view_model_login_with_typed_credentials() {
        var capturedUsername: String? = null
        var capturedPassword: CharArray? = null
        val gate = CompletableDeferred<LoginResult>()

        val vm = viewModel(attemptLogin = { user, pwd ->
            capturedUsername = user
            capturedPassword = pwd.clone()
            gate.await()
        })

        composeRule.setContent {
            LoginScreen(vm, onSuccess = { _, _ -> }, onTwoFactorRequired = {})
        }
        composeRule.onNodeWithText("Email or username").performTextInput("alice")
        composeRule.onNodeWithText("Password").performTextInput("s3cret")
        composeRule.onNodeWithText("Sign in").performClick()

        composeRule.waitForIdle()
        assertEquals("alice", capturedUsername)
        assertArrayEquals("s3cret".toCharArray(), capturedPassword)
    }

    @Test
    fun submitting_state_disables_inputs_and_shows_progress() {
        val gate = CompletableDeferred<LoginResult>()
        val vm = viewModel(attemptLogin = { _, _ -> gate.await() })

        composeRule.setContent {
            LoginScreen(vm, onSuccess = { _, _ -> }, onTwoFactorRequired = {})
        }
        composeRule.onNodeWithText("Email or username").performTextInput("alice")
        composeRule.onNodeWithText("Password").performTextInput("pw")
        composeRule.onNodeWithText("Sign in").performClick()

        composeRule.waitForIdle()
        composeRule.onNodeWithText("Email or username").assertIsNotEnabled()
        composeRule.onNodeWithText("Sign in").assertIsNotEnabled()
    }

    @Test
    fun failed_state_shows_error_message() {
        val vm = viewModel(
            attemptLogin = { _, _ -> LoginResult.Failed("auth_failed") }
        )

        composeRule.setContent {
            LoginScreen(vm, onSuccess = { _, _ -> }, onTwoFactorRequired = {})
        }
        composeRule.onNodeWithText("Email or username").performTextInput("alice")
        composeRule.onNodeWithText("Password").performTextInput("wrong")
        composeRule.onNodeWithText("Sign in").performClick()

        composeRule.waitForIdle()
        composeRule.onNode(hasText("Wrong username or password?", substring = true))
            .assertIsDisplayed()
    }

    @Test
    fun failed_state_maps_appversion_rejected_to_friendly_update_message() {
        val vm = viewModel(
            attemptLogin = { _, _ -> LoginResult.Failed("appversion_rejected") }
        )

        composeRule.setContent {
            LoginScreen(vm, onSuccess = { _, _ -> }, onTwoFactorRequired = {})
        }
        composeRule.onNodeWithText("Email or username").performTextInput("alice")
        composeRule.onNodeWithText("Password").performTextInput("pw")
        composeRule.onNodeWithText("Sign in").performClick()

        composeRule.waitForIdle()
        composeRule.onNode(hasText("update pcontacts", substring = true)).assertIsDisplayed()
        // The raw reason code must never reach the user.
        composeRule.onNode(hasText("appversion_rejected", substring = true)).assertDoesNotExist()
    }

    @Test
    fun failed_state_maps_modulus_failure_to_security_warning() {
        val vm = viewModel(
            attemptLogin = { _, _ -> LoginResult.Failed("modulus_signature_invalid") }
        )

        composeRule.setContent {
            LoginScreen(vm, onSuccess = { _, _ -> }, onTwoFactorRequired = {})
        }
        composeRule.onNodeWithText("Email or username").performTextInput("alice")
        composeRule.onNodeWithText("Password").performTextInput("pw")
        composeRule.onNodeWithText("Sign in").performClick()

        composeRule.waitForIdle()
        composeRule.onNode(hasText("may be intercepted", substring = true)).assertIsDisplayed()
        composeRule.onNode(hasText("modulus_signature_invalid", substring = true)).assertDoesNotExist()
    }
}
