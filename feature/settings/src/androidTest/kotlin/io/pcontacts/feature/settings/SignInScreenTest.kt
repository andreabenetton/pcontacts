// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.feature.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SignInScreenTest {

    @get:Rule val composeRule = createComposeRule()

    @Test
    fun shows_the_settings_shell_with_only_the_account_section_and_signs_in_on_tap() {
        var signedIn = false
        composeRule.setContent {
            SignInScreen(
                loading = false,
                contactsPermissionGranted = true,
                onSignIn = { signedIn = true },
                onOpenDeGoogledRoms = {}
            )
        }
        composeRule.onNodeWithText("Account").assertIsDisplayed()
        composeRule.onNodeWithText("Sign in").assertIsDisplayed().performClick()
        assertTrue(signedIn)
        composeRule.onNodeWithText("de-Googled ROM", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("You were signed out", substring = true).assertDoesNotExist()
    }

    @Test
    fun after_the_storage_upgrade_the_screen_explains_the_sign_out() {
        composeRule.setContent {
            SignInScreen(
                loading = false,
                contactsPermissionGranted = true,
                onSignIn = {},
                onOpenDeGoogledRoms = {},
                storageUpgradeNotice = true
            )
        }
        composeRule.onNodeWithText("You were signed out", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Sign in").assertIsDisplayed()
    }

    @Test
    fun disables_sign_in_while_loading() {
        composeRule.setContent {
            SignInScreen(loading = true, contactsPermissionGranted = true, onSignIn = {}, onOpenDeGoogledRoms = {})
        }
        composeRule.onNodeWithText("Sign in").assertIsNotEnabled()
    }

    @Test
    fun before_contacts_permission_is_granted_the_notice_links_the_rom_explanation() {
        var opened = 0
        composeRule.setContent {
            SignInScreen(
                loading = false,
                contactsPermissionGranted = false,
                onSignIn = {},
                onOpenDeGoogledRoms = { opened++ }
            )
        }
        composeRule.onNodeWithText("de-Googled ROM", substring = true).assertIsDisplayed()
        composeRule.clickLink("de-Googled ROM")
        assertEquals(1, opened)
    }
}
