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
 * (Compose + Material3). The Activity-level manifest theme is a minimal
 * `android:Theme.Material.Light.NoActionBar` for window setup before
 * `setContent` runs; all real theming happens here in Compose.
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
