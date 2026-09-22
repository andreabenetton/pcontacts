// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.feature.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SignInScreenTest {

    @get:Rule val composeRule = createComposeRule()

    @Test
    fun shows_the_settings_shell_with_only_the_account_section_and_signs_in_on_tap() {
        var signedIn = false
        composeRule.setContent {
            SignInScreen(loading = false, onSignIn = { signedIn = true })
        }
        composeRule.onNodeWithText("Account").assertIsDisplayed()
        composeRule.onNodeWithText("Sign in").assertIsDisplayed().performClick()
        assertTrue(signedIn)
    }

    @Test
    fun sign_in_is_disabled_while_the_account_state_loads() {
        composeRule.setContent {
            SignInScreen(loading = true, onSignIn = {})
        }
        composeRule.onNodeWithText("Sign in").assertIsNotEnabled()
    }
}
