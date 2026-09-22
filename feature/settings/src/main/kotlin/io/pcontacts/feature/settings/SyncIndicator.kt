// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.feature.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Glyph plus label at the given text style; the glyph scales with [glyphSize]. */
@Composable
fun SyncIndicator(
    tone: SyncTone,
    text: String,
    style: TextStyle = MaterialTheme.typography.labelMedium,
    glyphSize: Dp = 14.dp,
    modifier: Modifier = Modifier
) {
    val tint = tone.tint()
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        if (tone == SyncTone.RUNNING) {
            CircularProgressIndicator(
                modifier = Modifier.size(glyphSize),
                strokeWidth = (glyphSize / 7).coerceAtLeast(2.dp),
                color = tint
            )
        } else {
            Icon(
                imageVector = when (tone) {
                    SyncTone.OK -> Icons.Default.Check
                    SyncTone.WARN -> Icons.Default.Warning
                    else -> Icons.Default.Info
                },
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(glyphSize)
            )
        }
        Spacer(Modifier.width(if (glyphSize > 16.dp) 12.dp else 4.dp))
        Text(text = text, style = style, color = tint)
    }
}

@Composable
fun SyncTone.tint(): Color = when (this) {
    SyncTone.RUNNING, SyncTone.OK -> MaterialTheme.colorScheme.primary
    SyncTone.WARN -> MaterialTheme.colorScheme.error
    SyncTone.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
}
