// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.feature.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * The full-width outlined action of the Contacts section. A 2 dp violet
 * border and violet label while usable; grey border matching the 38 %
 * disabled label when not. Material's own 1 dp, 12 %-alpha disabled
 * outline vanishes on the dark theme and the control stops reading as a
 * button.
 */
@Composable
internal fun ActionButton(enabled: Boolean, onClick: () -> Unit, @StringRes textRes: Int) {
    val border = if (enabled) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }
    OutlinedButton(
        enabled = enabled,
        onClick = onClick,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
        border = BorderStroke(2.dp, border),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(stringResource(textRes))
    }
}
