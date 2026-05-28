// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Second-stage login screen: TOTP code entry. Hosted by the same
 * Activity that hosts LoginScreen; navigation happens when LoginScreen
 * fires `onTwoFactorRequired`.
 *
 * Renders nothing useful until the ViewModel is in a 2FA-related state
 * (TwoFactorRequired, TwoFactorSubmitting, TwoFactorFailed). On Success
 * the Activity-provided `onSuccess` fires once via LaunchedEffect — not
 * on every recomposition.
 *
 * `onCancel` returns control to the LoginScreen (the host resets the
 * ViewModel back to Idle). Useful if the user realises the TOTP device
 * is unavailable and wants to restart with a different account.
 */
@Composable
fun TwoFactorScreen(
    viewModel: LoginViewModel,
    onSuccess: (uid: String, username: String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    var code by remember { mutableStateOf("") }
    val submitting = state is LoginUiState.TwoFactorSubmitting

    Column(
        modifier = modifier.fillMaxSize().padding(PaddingValues(24.dp)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.two_factor_title),
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.two_factor_subtitle),
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = code,
            // Restrict to digits — the TOTP path doesn't accept letters and
            // typing them just produces noise. The 8-char cap covers both
            // TOTP (6) and Proton recovery codes (8).
            onValueChange = { input -> code = input.filter(Char::isDigit).take(8) },
            label = { Text(stringResource(R.string.two_factor_code_label)) },
            singleLine = true,
            enabled = !submitting,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(24.dp))

        Button(
            enabled = !submitting && code.length >= 6,
            onClick = {
                val pending = code
                viewModel.submitTwoFactor(pending)
                // Clear immediately — the lambda has captured the value.
                code = ""
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.two_factor_verify))
        }

        Spacer(Modifier.height(8.dp))

        TextButton(
            enabled = !submitting,
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.two_factor_cancel))
        }

        Spacer(Modifier.height(16.dp))

        when (val s = state) {
            LoginUiState.Idle,
            LoginUiState.Submitting,
            is LoginUiState.Failed,
            is LoginUiState.TwoFactorRequired,
            is LoginUiState.HumanVerificationRequired -> Unit
            is LoginUiState.TwoFactorSubmitting ->
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            is LoginUiState.TwoFactorFailed -> Text(
                text = friendlyTotpError(s.reason),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
            is LoginUiState.Success -> LaunchedEffect(s.uid) { onSuccess(s.uid, s.username) }
        }
    }
}
