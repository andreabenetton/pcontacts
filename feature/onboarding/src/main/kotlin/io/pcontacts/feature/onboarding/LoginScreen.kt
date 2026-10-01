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
import androidx.compose.ui.platform.LocalAutofillManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Login screen. Pure Composable — no Activity coupling, no DI framework.
 * The hosting Activity constructs the LoginViewModel (which carries the
 * orchestrator dependency) and passes it down, and wraps the screen in
 * the app's shell (top bar); the screen lays out like a Settings
 * section so signing in looks like the screen that follows it.
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
    onSuccess: (uid: String, username: String) -> Unit,
    onTwoFactorRequired: (uid: String) -> Unit,
    onHumanVerificationRequired: (verificationUrl: String?) -> Unit = {},
    modifier: Modifier = Modifier,
    /** Why the user is asked to sign in (signed out, storage upgrade), under the header. */
    notices: @Composable () -> Unit = {},
    /** The bottom of the screen, as on the app's signed-out screen (the non-affiliation statement). */
    footer: @Composable () -> Unit = {}
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    var username by rememberSaveable { mutableStateOf("") }
    // Password is intentionally NOT rememberSaveable — we don't want it
    // surviving process death or landing in saved-state bundles.
    var password by remember { mutableStateOf("") }
    val editable = state !is LoginUiState.Submitting
    val canSubmit = editable && username.isNotBlank() && password.isNotEmpty()
    val failed = state as? LoginUiState.Failed
    val wrongCredentials = failed?.reason == "auth_failed"
    // The form opens by itself when there is no account: start typing at once.
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }
    val submit = {
        val pwd = password.toCharArray()
        viewModel.login(username.trim(), pwd)
        // Clear the in-memory String now; the CharArray is in-flight.
        password = ""
    }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        // The form scrolls, so the keyboard never hides the button on a small screen.
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            FormHeader(titleRes = R.string.login_title, subtitleRes = R.string.login_subtitle)
            notices()

            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text(stringResource(R.string.login_username_label)) },
                singleLine = true,
                enabled = editable,
                isError = wrongCredentials,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Next
                ),
                // Password managers (Proton Pass, Bitwarden…) recognise the field and offer to fill it.
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus)
                    .semantics { contentType = ContentType.Username }
            )
            Spacer(Modifier.height(12.dp))

            PasswordField(
                value = password,
                onValueChange = { password = it },
                editable = editable,
                wrongCredentials = wrongCredentials,
                failed = failed != null,
                onDone = { if (canSubmit) submit() }
            )
            Spacer(Modifier.height(24.dp))

            SignInButton(textRes = R.string.login_sign_in, enabled = canSubmit, onClick = submit)

            Spacer(Modifier.height(16.dp))

            LoginStatusView(
                state = state,
                onSuccess = onSuccess,
                onTwoFactorRequired = onTwoFactorRequired,
                onHumanVerificationRequired = onHumanVerificationRequired
            )
            Spacer(Modifier.height(16.dp))
        }
        footer()
    }
}

/**
 * The password, hidden unless shown. [failed]: the last attempt failed and the field was wiped
 * with it (a password never outlives a submit), so the field asks for it again.
 */
// One parameter per input the field reflects; the screen owns the state.
@Suppress("LongParameterList")
@Composable
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    editable: Boolean,
    wrongCredentials: Boolean,
    failed: Boolean,
    onDone: () -> Unit
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(R.string.login_password_label)) },
        singleLine = true,
        enabled = editable,
        isError = wrongCredentials,
        supportingText = if (failed) {
            { Text(stringResource(R.string.login_password_again)) }
        } else {
            null
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            autoCorrectEnabled = false,
            imeAction = ImeAction.Done
        ),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            TextButton(onClick = { visible = !visible }) {
                Text(stringResource(if (visible) R.string.login_password_hide else R.string.login_password_show))
            }
        },
        // Password managers (Proton Pass, Bitwarden…) recognise the field and offer to fill it.
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentType = ContentType.Password }
    )
}

@Composable
private fun LoginStatusView(
    state: LoginUiState,
    onSuccess: (uid: String, username: String) -> Unit,
    onTwoFactorRequired: (uid: String) -> Unit,
    onHumanVerificationRequired: (verificationUrl: String?) -> Unit
) {
    // Proton accepted the password: a password manager may now offer to save it.
    val autofill = LocalAutofillManager.current
    when (state) {
        LoginUiState.Idle -> Unit
        LoginUiState.Submitting ->
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        is LoginUiState.Success -> LaunchedEffect(state.uid) {
            autofill?.commit()
            onSuccess(state.uid, state.username)
        }
        is LoginUiState.TwoFactorRequired -> LaunchedEffect(state.uid) {
            autofill?.commit()
            onTwoFactorRequired(state.uid)
        }
        is LoginUiState.HumanVerificationRequired -> LaunchedEffect(state.verificationUrl) {
            onHumanVerificationRequired(state.verificationUrl)
        }
        // The 2FA-side states belong to TwoFactorScreen; the host Activity
        // is expected to have navigated there as soon as we crossed into
        // TwoFactorRequired. If we still observe them here it's a stale
        // recomposition — render nothing.
        is LoginUiState.TwoFactorSubmitting,
        is LoginUiState.TwoFactorHumanVerificationRequired,
        is LoginUiState.KeyDerivationHumanVerificationRequired,
        is LoginUiState.TwoFactorFailed -> Unit
        is LoginUiState.Failed -> Text(
            text = friendlyError(state.reason),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun friendlyError(reason: String): String = when (reason) {
    "info_failed" -> stringResource(R.string.login_error_unreachable)
    "srp_failed" -> stringResource(R.string.login_error_srp)
    "auth_failed" -> stringResource(R.string.login_error_credentials)
    "server_proof_decode_failed",
    "server_proof_mismatch" -> stringResource(R.string.login_error_proof_mismatch)
    "appversion_rejected" -> stringResource(R.string.login_error_app_version)
    "modulus_unsigned",
    "modulus_signature_invalid",
    "modulus_pin_missing" -> stringResource(R.string.login_error_modulus)
    "key_derivation_failed" -> stringResource(R.string.login_error_key_derivation)
    else -> stringResource(R.string.login_error_generic)
}

@Composable
internal fun friendlyTotpError(reason: String): String = when (reason) {
    "two_factor_failed" -> stringResource(R.string.two_factor_error_unreachable)
    "two_factor_rejected" -> stringResource(R.string.two_factor_error_rejected)
    "no_session" -> stringResource(R.string.two_factor_error_session_expired)
    "verification_rejected" -> stringResource(R.string.two_factor_error_verification_rejected)
    "unexpected_state" -> stringResource(R.string.two_factor_error_unexpected)
    else -> stringResource(R.string.two_factor_error_generic)
}
