// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * Material3 theme for pcontacts. Follows the ProtonVPN/android-app stack
 * (Compose + Material3); no Material3-XML or AppCompat theming is involved
 * at the Composable layer. The Activity-level manifest theme remains
 * `Theme.AppCompat.DayNight.NoActionBar` only because Android requires a
 * non-Compose theme for window/status-bar setup before `setContent` runs.
 *
 * Color overrides land here once we have brand palette decisions; for now
 * we use Material3's stock light/dark color schemes so previews and the
 * system Material You theme behave as Android expects.
 */
@Composable
fun PcontactsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme()
    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
