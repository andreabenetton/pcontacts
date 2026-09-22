// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.feature.onboarding

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/** The green of the Settings screen's Sign in button: the one colour that means "this gets you in". */
private val SignInGreen = Color(0xFF2E9E5B)

/** A Settings-style section header (title in the accent colour) with the sentence that explains the form. */
@Composable
internal fun FormHeader(titleRes: Int, subtitleRes: Int) {
    Spacer(Modifier.height(24.dp))
    Text(
        text = stringResource(titleRes),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary
    )
    Spacer(Modifier.height(8.dp))
    Text(text = stringResource(subtitleRes), style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(16.dp))
}

/** The form's primary action, in the same green outline as Settings' Sign in. */
@Composable
internal fun SignInButton(textRes: Int, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        enabled = enabled,
        onClick = onClick,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = SignInGreen),
        border = BorderStroke(1.dp, if (enabled) SignInGreen else MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(stringResource(textRes))
    }
}
