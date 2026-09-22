// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.feature.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ContactsAccessScreenTest {

    @get:Rule val composeRule = createComposeRule()

    @Test
    fun os_apps_notice_links_the_de_googled_rom_phrase_to_the_explanation() {
        var opened = 0
        composeRule.setContent {
            ContactsAccessScreen(
                kind = ContactsAccessKind.SYSTEM,
                apps = listOf(ContactsAccessApp("Phone", "com.example.dialer")),
                permissionRoute = ContactsPermissionRoute.DIRECT,
                onOpenPermission = {},
                onOpenDeGoogledRoms = { opened++ },
                onBack = {}
            )
        }
        composeRule.onNodeWithText("de-Googled ROM", substring = true).assertIsDisplayed()
        composeRule.clickLink("de-Googled ROM")
        assertEquals(1, opened)
    }

    @Test
    fun user_apps_notice_has_no_link() {
        composeRule.setContent {
            ContactsAccessScreen(
                kind = ContactsAccessKind.USER,
                apps = emptyList(),
                permissionRoute = ContactsPermissionRoute.DIRECT,
                onOpenPermission = {},
                onOpenDeGoogledRoms = {},
                onBack = {}
            )
        }
        composeRule.onNodeWithText("de-Googled ROM", substring = true).assertDoesNotExist()
    }
}
