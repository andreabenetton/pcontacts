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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
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
    modifier: Modifier = Modifier,
    /** The bottom of the screen, as on the password form (the non-affiliation statement). */
    footer: @Composable () -> Unit = {}
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // [V] As in Proton's web client (MinimalLoginContainer): the authenticator code, or one of the
    // recovery codes, which are free text and go to the same request.
    var recovery by rememberSaveable { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var autoSubmitted by remember { mutableStateOf(false) }
    // Input stays locked while the verification WebView is up, as while a code is in flight.
    val submitting = state is LoginUiState.TwoFactorSubmitting ||
        state is LoginUiState.TwoFactorHumanVerificationRequired ||
        state is LoginUiState.KeyDerivationHumanVerificationRequired
    val entered = code.filterNot(Char::isWhitespace)
    val canSubmit = !submitting && if (recovery) entered.isNotEmpty() else entered.length == TOTP_LENGTH
    val submit = {
        viewModel.submitTwoFactor(entered)
        // Clear immediately — the call has the value.
        code = ""
    }
    // [V] The web client sends the authenticator code by itself at the 6th digit, once.
    LaunchedEffect(entered) {
        if (!recovery && !autoSubmitted && canSubmit) {
            autoSubmitted = true
            submit()
        }
    }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            FormHeader(
                titleRes = R.string.two_factor_title,
                subtitleRes = if (recovery) R.string.two_factor_recovery_subtitle else R.string.two_factor_subtitle
            )
            CodeField(
                code = code,
                onCodeChange = { input ->
                    code = if (recovery) input.take(RECOVERY_MAX) else input.filter(Char::isDigit).take(TOTP_LENGTH)
                },
                recovery = recovery,
                enabled = !submitting,
                onDone = { if (canSubmit) submit() }
            )
            Spacer(Modifier.height(24.dp))

            SignInButton(textRes = R.string.two_factor_verify, enabled = canSubmit, onClick = submit)

            Spacer(Modifier.height(8.dp))

            TextButton(
                enabled = !submitting,
                onClick = {
                    recovery = !recovery
                    code = ""
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(if (recovery) R.string.two_factor_use_code else R.string.two_factor_use_recovery))
            }
            TextButton(
                enabled = !submitting,
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.two_factor_cancel))
            }

            Spacer(Modifier.height(16.dp))

            TwoFactorStatusView(state, onSuccess, onHumanVerificationRequired)
            Spacer(Modifier.height(16.dp))
        }
        footer()
    }
}

/**
 * The code box: digits only for the authenticator code, tagged so a password manager can fill
 * it; free text for a recovery code. Focus and the keyboard come with the screen and each switch.
 */
@Composable
private fun CodeField(
    code: String,
    onCodeChange: (String) -> Unit,
    recovery: Boolean,
    enabled: Boolean,
    onDone: () -> Unit
) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(recovery) {
        focus.requestFocus()
        keyboard?.show()
    }
    OutlinedTextField(
        value = code,
        onValueChange = onCodeChange,
        label = {
            Text(stringResource(if (recovery) R.string.two_factor_recovery_label else R.string.two_factor_code_label))
        },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (recovery) KeyboardType.Ascii else KeyboardType.NumberPassword,
            autoCorrectEnabled = false,
            imeAction = ImeAction.Done
        ),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focus)
            .semantics { if (!recovery) contentType = AUTHENTICATOR_CODE }
    )
}

/** Progress, the error of a rejected code, and the hand-offs (verification, success). */
@Composable
private fun TwoFactorStatusView(
    state: LoginUiState,
    onSuccess: (uid: String, username: String) -> Unit,
    onHumanVerificationRequired: (verificationUrl: String?) -> Unit
) {
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
        is LoginUiState.KeyDerivationHumanVerificationRequired -> {
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

/** An authenticator code: 6 digits. */
private const val TOTP_LENGTH = 6

/** A generous cap for a recovery code, whose format the web client does not constrain. */
private const val RECOVERY_MAX = 64

/** Android's autofill hint for an authenticator-app code (View.AUTOFILL_HINT_2FA_APP_OTP). */
private val AUTHENTICATOR_CODE = ContentType("2faAppOTPCode")
