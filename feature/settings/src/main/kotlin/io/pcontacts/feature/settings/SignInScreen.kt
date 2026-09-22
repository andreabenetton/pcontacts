// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.feature.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/** The one colour the app uses for "this gets you in": the counterpart of Sign out's error red. */
private val SignInGreen = Color(0xFF2E9E5B)

/**
 * What the app shows before there is a Proton account: the same
 * Settings shell as when signed in, reduced to the Account section, so
 * signing in and signing out happen in the same place on the same
 * screen. [loading] disables the button while the account state is
 * still being read.
 */
@Composable
fun SignInScreen(
    loading: Boolean,
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHost: @Composable () -> Unit = {}
) {
    Scaffold(
        modifier = modifier,
        topBar = { AppTopBar() },
        snackbarHost = snackbarHost
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp)) {
            SectionHeader(R.string.settings_section_account)
            Text(
                text = stringResource(R.string.settings_sign_in_detail),
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                enabled = !loading,
                onClick = onSignIn,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = SignInGreen),
                border = BorderStroke(1.dp, SignInGreen),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.settings_sign_in))
            }
        }
    }
}
