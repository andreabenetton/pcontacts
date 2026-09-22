// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.feature.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration

/**
 * A sentence whose `%1$s` is a tappable phrase. The phrase is its own
 * string resource, so every translation keeps it a link wherever the
 * grammar puts it. Tag of the link: the phrase resource id, which is
 * what a UI test looks for.
 */
@Composable
internal fun LinkedText(
    templateRes: Int,
    phraseRes: Int,
    onClick: () -> Unit,
    style: TextStyle = MaterialTheme.typography.bodySmall,
    color: Color = Color.Unspecified,
    modifier: Modifier = Modifier
) {
    val phrase = stringResource(phraseRes)
    val sentence = stringResource(templateRes, phrase)
    val start = sentence.indexOf(phrase)
    val linkStyle = TextLinkStyles(
        style = SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline)
    )
    val text = buildAnnotatedString {
        append(sentence)
        if (start >= 0) {
            addLink(
                LinkAnnotation.Clickable(tag = phraseRes.toString(), styles = linkStyle) { onClick() },
                start,
                start + phrase.length
            )
        }
    }
    Text(text = text, style = style, color = color, modifier = modifier)
}
