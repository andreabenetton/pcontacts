// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.feature.onboarding

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The unlock step of two-password mode (issue #65): after the login password (and the 2FA code
 * when it is on), the account's keys open only with the second password — Proton's "mailbox
 * password". `[V]` WebClients `loginActions.handleUnlock`. Hosted by the same Activity and
 * view model as [LoginScreen] and [TwoFactorScreen]; the password is handed over as a CharArray
 * and cleared from the field at once.
 */
@Composable
fun SecondPasswordScreen(
    viewModel: LoginViewModel,
    onSuccess: (uid: String, username: String) -> Unit,
    onCancel: () -> Unit,
    onHumanVerificationRequired: (verificationUrl: String?) -> Unit = {},
    modifier: Modifier = Modifier,
    /** The bottom of the screen, as on the password form (the non-affiliation statement). */
    footer: @Composable () -> Unit = {}
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Not saveable: the password must not outlive the screen in a saved-state bundle.
    var password by remember { mutableStateOf("") }
    val submitting = state is LoginUiState.SecondPasswordSubmitting ||
        state is LoginUiState.KeyDerivationHumanVerificationRequired
    val canSubmit = !submitting && password.isNotEmpty()
    val submit = {
        viewModel.submitSecondPassword(password.toCharArray())
        password = ""
    }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            FormHeader(titleRes = R.string.second_password_title, subtitleRes = R.string.second_password_subtitle)
            PasswordField(
                value = password,
                onValueChange = { password = it },
                editable = !submitting,
                wrongCredentials = (state as? LoginUiState.SecondPasswordFailed)?.reason == "second_password_rejected",
                failed = false,
                onDone = { if (canSubmit) submit() },
                labelRes = R.string.second_password_label,
                autofill = false,
                focusRequester = focus
            )
            Spacer(Modifier.height(24.dp))

            SignInButton(textRes = R.string.second_password_unlock, enabled = canSubmit, onClick = submit)

            Spacer(Modifier.height(8.dp))

            TextButton(enabled = !submitting, onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.two_factor_cancel))
            }

            Spacer(Modifier.height(16.dp))

            SecondPasswordStatusView(state, onSuccess, onHumanVerificationRequired)
            Spacer(Modifier.height(16.dp))
        }
        footer()
    }
}

/** Progress, the error of a refused attempt, and the hand-offs (verification, success). */
@Composable
private fun SecondPasswordStatusView(
    state: LoginUiState,
    onSuccess: (uid: String, username: String) -> Unit,
    onHumanVerificationRequired: (verificationUrl: String?) -> Unit
) {
    when (val s = state) {
        is LoginUiState.SecondPasswordSubmitting -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        is LoginUiState.KeyDerivationHumanVerificationRequired -> {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            LaunchedEffect(s.verificationUrl) { onHumanVerificationRequired(s.verificationUrl) }
        }
        is LoginUiState.SecondPasswordFailed -> Text(
            text = stringResource(secondPasswordErrorRes(s.reason)),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium
        )
        is LoginUiState.Success -> LaunchedEffect(s.uid) { onSuccess(s.uid, s.username) }
        else -> Unit
    }
}

/** What a refused second-password attempt says; Proton's own wording for a wrong one. */
internal fun secondPasswordErrorRes(reason: String): Int = when (reason) {
    "second_password_rejected" -> R.string.second_password_error_rejected
    "no_session" -> R.string.two_factor_error_session_expired
    "verification_rejected" -> R.string.two_factor_error_verification_rejected
    "unexpected_state" -> R.string.two_factor_error_unexpected
    else -> R.string.login_error_generic
}
