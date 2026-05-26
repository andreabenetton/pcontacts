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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Login screen. Pure Composable — no Activity coupling, no DI framework.
 * The hosting Activity constructs the LoginViewModel (which carries the
 * orchestrator dependency) and passes it down.
 *
 * `onSuccess` / `onTwoFactorRequired` are navigation hooks the host
 * Activity wires up; the screen itself doesn't know what comes next.
 *
 * Password handling note: Compose's TextField currently surfaces a String
 * (not a CharArray); we wrap to CharArray on submit and zero the original
 * after `attemptLogin` returns. The brief `password.toString()` allocation
 * is an `[A]` compromise versus the cost of a custom char-array
 * TextField; ADR-0009 calls out that the JVM cannot guarantee memory
 * zeroization either way.
 */
@Composable
fun LoginScreen(
    viewModel: LoginViewModel,
    onSuccess: (uid: String) -> Unit,
    onTwoFactorRequired: (uid: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    var username by rememberSaveable { mutableStateOf("") }
    // Password is intentionally NOT rememberSaveable — we don't want it
    // surviving process death or landing in saved-state bundles.
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxSize().padding(PaddingValues(24.dp)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.login_title),
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text(stringResource(R.string.login_username_label)) },
            singleLine = true,
            enabled = state !is LoginUiState.Submitting,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(stringResource(R.string.login_password_label)) },
            singleLine = true,
            enabled = state !is LoginUiState.Submitting,
            visualTransformation = if (passwordVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                TextButton(onClick = { passwordVisible = !passwordVisible }) {
                    Text(stringResource(if (passwordVisible) R.string.login_password_hide else R.string.login_password_show))
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(24.dp))

        Button(
            enabled = state !is LoginUiState.Submitting &&
                username.isNotBlank() && password.isNotEmpty(),
            onClick = {
                val pwd = password.toCharArray()
                viewModel.login(username.trim(), pwd)
                // Clear the in-memory String now; the CharArray is in-flight.
                password = ""
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.login_sign_in))
        }

        Spacer(Modifier.height(16.dp))

        when (val s = state) {
            LoginUiState.Idle -> Unit
            LoginUiState.Submitting ->
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            is LoginUiState.Success -> LaunchedEffect(s.uid) { onSuccess(s.uid) }
            is LoginUiState.TwoFactorRequired -> LaunchedEffect(s.uid) { onTwoFactorRequired(s.uid) }
            // The 2FA-side states belong to TwoFactorScreen; the host Activity
            // is expected to have navigated there as soon as we crossed into
            // TwoFactorRequired. If we still observe them here it's a stale
            // recomposition — render nothing.
            is LoginUiState.TwoFactorSubmitting,
            is LoginUiState.TwoFactorFailed -> Unit
            is LoginUiState.Failed -> Text(
                text = friendlyError(s.reason),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

private fun friendlyError(reason: String): String = when (reason) {
    "info_failed" -> "Could not reach Proton. Check your connection and try again."
    "srp_failed" -> "Internal SRP error. Try again or report this."
    "auth_failed" -> "Sign-in failed. Wrong username or password?"
    "server_proof_decode_failed",
    "server_proof_mismatch" -> "Server proof mismatch. Your connection may be intercepted."
    else -> "Sign-in failed: $reason"
}

internal fun friendlyTotpError(reason: String): String = when (reason) {
    "two_factor_failed" -> "Could not reach Proton. Check your connection and try again."
    "two_factor_rejected" -> "Wrong code. Try again."
    "no_session" -> "Sign-in session expired. Please start over."
    "unexpected_state" -> "Unexpected response from server. Try again or restart sign-in."
    else -> "Two-factor verification failed: $reason"
}
