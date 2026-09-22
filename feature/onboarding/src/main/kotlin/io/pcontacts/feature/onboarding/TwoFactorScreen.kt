// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.feature.onboarding

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
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
    onHumanVerificationRequired: (verificationUrl: String?) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    var code by remember { mutableStateOf("") }
    // Input stays locked while the verification WebView is up, as while a code is in flight.
    val submitting = state is LoginUiState.TwoFactorSubmitting || state is LoginUiState.TwoFactorHumanVerificationRequired
    val canSubmit = !submitting && code.length >= 6
    val submit = {
        val pending = code
        viewModel.submitTwoFactor(pending)
        // Clear immediately — the lambda has captured the value.
        code = ""
    }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        FormHeader(titleRes = R.string.two_factor_title, subtitleRes = R.string.two_factor_subtitle)

        OutlinedTextField(
            value = code,
            // Restrict to digits — the TOTP path doesn't accept letters and
            // typing them just produces noise. The 8-char cap covers both
            // TOTP (6) and Proton recovery codes (8).
            onValueChange = { input -> code = input.filter(Char::isDigit).take(8) },
            label = { Text(stringResource(R.string.two_factor_code_label)) },
            singleLine = true,
            enabled = !submitting,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (canSubmit) submit() }),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(24.dp))

        SignInButton(textRes = R.string.two_factor_verify, enabled = canSubmit, onClick = submit)

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
            is LoginUiState.TwoFactorHumanVerificationRequired -> {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                LaunchedEffect(s.verificationUrl) { onHumanVerificationRequired(s.verificationUrl) }
            }
            is LoginUiState.TwoFactorFailed -> Text(
                text = friendlyTotpError(s.reason),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
            is LoginUiState.Success -> LaunchedEffect(s.uid) { onSuccess(s.uid, s.username) }
        }
    }
}
