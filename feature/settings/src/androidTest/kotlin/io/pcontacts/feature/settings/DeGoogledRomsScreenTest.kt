// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.feature.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class DeGoogledRomsScreenTest {

    @get:Rule val composeRule = createComposeRule()

    @Test
    fun lists_every_project_and_opens_its_website_through_the_host() {
        val opened = mutableListOf<String>()
        composeRule.setContent {
            DeGoogledRomsScreen(onOpenWebsite = { opened += it; true }, onBack = {})
        }
        composeRule.onNodeWithText("De-Googled Android ROMs").assertIsDisplayed()
        composeRule.onNodeWithText("GrapheneOS").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Open the GrapheneOS website").performClick()
        assertEquals(listOf("https://grapheneos.org/"), opened)

        DE_GOOGLED_ROMS.forEach { rom ->
            composeRule.onNode(hasScrollAction()).performScrollToNode(hasText(rom.name))
            composeRule.onNodeWithText(rom.name).assertIsDisplayed()
        }
    }

    @Test
    fun says_so_when_the_device_cannot_open_a_link() {
        composeRule.setContent {
            DeGoogledRomsScreen(onOpenWebsite = { false }, onBack = {})
        }
        composeRule.onNodeWithContentDescription("Open the GrapheneOS website").performClick()
        composeRule.onNodeWithText("No app on this device can open web links.").assertIsDisplayed()
    }

    @Test
    fun back_arrow_calls_the_host() {
        var back = 0
        composeRule.setContent {
            DeGoogledRomsScreen(onOpenWebsite = { true }, onBack = { back++ })
        }
        composeRule.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, back)
    }
}
