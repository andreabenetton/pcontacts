// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.feature.settings

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult

/**
 * Taps the middle of [phrase] inside the one text node that contains
 * it: the way to activate a [androidx.compose.ui.text.LinkAnnotation]
 * from a test without relying on the link's internal semantics.
 */
internal fun SemanticsNodeInteractionsProvider.clickLink(phrase: String) {
    val node = onNode(hasText(phrase, substring = true))
    val layouts = mutableListOf<TextLayoutResult>()
    node.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
    val layout = layouts.single()
    val start = layout.layoutInput.text.text.indexOf(phrase)
    val box = layout.getBoundingBox(start + phrase.length / 2)
    node.performTouchInput { click(box.center) }
}
