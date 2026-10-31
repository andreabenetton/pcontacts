// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.pcontacts.core.sync.auth.LoginResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AndroidX ViewModel so login/2FA state survives Activity rotation.
 *
 * `attemptLogin` / `submitTotp` are function references (not interfaces)
 * to keep the test seam tight: a fake just supplies a lambda returning
 * a canned `LoginResult`, no mocking library required.
 */
class LoginViewModel(
    private val attemptLogin: suspend (username: String, password: CharArray) -> LoginResult,
    private val submitTotp: suspend (code: String) -> LoginResult,
    /** Resumes a login that a 9001 interrupted after the code was accepted (`HvStage.KEY_DERIVATION`). */
    private val retryKeyDerivation: suspend () -> LoginResult = { LoginResult.Failed(reason = "unexpected_state") },
    /** Tells the orchestrator the user left the flow: stash zeroed, half session wiped. */
    private val abortLogin: () -> Unit = {},
    /** Two-password mode: hands the second password to the orchestrator, which zeroes it. */
    private val submitSecondPassword: suspend (CharArray) -> LoginResult = {
        LoginResult.Failed(reason = "unexpected_state")
    },
    private val workDispatcher: CoroutineDispatcher = Dispatchers.Default
) : ViewModel() {

    private val _uiState = MutableStateFlow<LoginUiState>(LoginUiState.Idle)
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    private var pendingJob: Job? = null

    // Retained across the captcha WebView round-trip so retryAfterVerification()
    // can re-invoke /auth without the user re-typing credentials. Cleared
    // on success/failure/reset. The orchestrator zeroes any CharArray it
    // receives in a finally block, so we keep our own copy and clone it
    // before each attemptLogin call.
    private var pendingUsername: String? = null
    private var pendingPassword: CharArray? = null

    // True once a captcha was solved during the 2FA phase: a second 9001 on
    // the resubmit then fails closed instead of looping into the WebView.
    private var twoFactorVerified = false

    // True while the second-password step is the current one: a captcha there resumes on it.
    private var onSecondPassword = false

    /** Whether a key-derivation captcha belongs to the second-password step (the host shows that screen). */
    val onSecondPasswordStep: Boolean get() = onSecondPassword

    fun login(username: String, password: CharArray) {
        if (_uiState.value is LoginUiState.Submitting) return

        zeroizePendingPassword()
        pendingUsername = username
        pendingPassword = password.copyOf()
        twoFactorVerified = false
        onSecondPassword = false

        _uiState.value = LoginUiState.Submitting
        pendingJob = viewModelScope.launch { runAttemptAndReduce(username, password) }
    }

    /**
     * Called by the hosting Activity after `HumanVerificationActivity`
     * returned RESULT_OK: the verification token is in `SecretStore`
     * and `HumanVerificationHeadersInterceptor` attaches it to every
     * following request (ADR-0019).
     *
     * Credentials phase: re-runs `/auth` with the retained credentials.
     * Two-factor phase: the SRP session is still live in the
     * orchestrator, so return to the TOTP screen for a fresh code — no
     * request is made here. `[U]` Proton accepting `/auth/2fa` on the
     * same session after a captcha is unvalidated live; a second 9001
     * fails closed to `verification_rejected`.
     */
    fun retryAfterVerification() {
        when (val current = _uiState.value) {
            is LoginUiState.HumanVerificationRequired -> {
                val username = pendingUsername ?: return
                val password = pendingPassword ?: return
                _uiState.value = LoginUiState.Submitting
                pendingJob = viewModelScope.launch { runAttemptAndReduce(username, password.copyOf()) }
            }
            is LoginUiState.TwoFactorHumanVerificationRequired -> {
                twoFactorVerified = true
                _uiState.value = LoginUiState.TwoFactorRequired(current.uid, current.username)
            }
            is LoginUiState.KeyDerivationHumanVerificationRequired -> resumeKeyDerivation(current)
            else -> return
        }
    }

    /**
     * The code (or the second password) is already in: finish on the kept session, on the step
     * the user was on; a further 9001 is a rejection.
     */
    private fun resumeKeyDerivation(current: LoginUiState.KeyDerivationHumanVerificationRequired) {
        val (uid, username) = current.uid to current.username
        val secondPassword = onSecondPassword
        _uiState.value = if (secondPassword) {
            LoginUiState.SecondPasswordSubmitting(uid, username)
        } else {
            LoginUiState.TwoFactorSubmitting(uid, username)
        }
        val failed = { reason: String ->
            if (secondPassword) {
                LoginUiState.SecondPasswordFailed(uid, username, reason)
            } else {
                LoginUiState.TwoFactorFailed(uid, username, reason)
            }
        }
        pendingJob = viewModelScope.launch {
            val result = withContext(workDispatcher) { retryKeyDerivation() }
            _uiState.value = when (result) {
                is LoginResult.Success -> LoginUiState.Success(result.uid, result.username)
                is LoginResult.Failed -> failed(result.reason)
                is LoginResult.HumanVerificationRequired -> failed("verification_rejected")
                is LoginResult.TwoFactorRequired, is LoginResult.SecondPasswordRequired -> failed("unexpected_state")
            }
        }
    }

    private suspend fun runAttemptAndReduce(username: String, password: CharArray) {
        val result = withContext(workDispatcher) { attemptLogin(username, password) }
        _uiState.value = when (result) {
            is LoginResult.Success -> {
                zeroizePendingPassword()
                LoginUiState.Success(result.uid, result.username)
            }
            is LoginResult.TwoFactorRequired -> {
                zeroizePendingPassword()
                LoginUiState.TwoFactorRequired(result.uid, result.username)
            }
            is LoginResult.SecondPasswordRequired -> {
                zeroizePendingPassword()
                onSecondPassword = true
                LoginUiState.SecondPasswordRequired(result.uid, result.username)
            }
            is LoginResult.HumanVerificationRequired ->
                LoginUiState.HumanVerificationRequired(result.verificationUrl)
            is LoginResult.Failed -> {
                zeroizePendingPassword()
                LoginUiState.Failed(result.reason, result.detail)
            }
        }
    }

    private fun zeroizePendingPassword() {
        pendingPassword?.fill('\u0000')
        pendingPassword = null
        pendingUsername = null
    }

    fun submitTwoFactor(code: String) {
        val current = _uiState.value
        val (uid, username) = when (current) {
            is LoginUiState.TwoFactorRequired -> current.uid to current.username
            is LoginUiState.TwoFactorFailed -> current.uid to current.username
            else -> return
        }

        _uiState.value = LoginUiState.TwoFactorSubmitting(uid, username)
        pendingJob = viewModelScope.launch {
            val result = withContext(workDispatcher) { submitTotp(code) }
            _uiState.value = when (result) {
                is LoginResult.Success -> LoginUiState.Success(result.uid, result.username)
                is LoginResult.Failed -> LoginUiState.TwoFactorFailed(uid, result.username ?: username, result.reason)
                is LoginResult.HumanVerificationRequired ->
                    // Proton can re-challenge mid-session. On /auth/2fa: once, open the
                    // WebView and come back for a fresh code; twice, stop. After the code
                    // was accepted: the WebView, then key derivation resumes — no new code.
                    when {
                        result.stage == LoginResult.HvStage.KEY_DERIVATION ->
                            LoginUiState.KeyDerivationHumanVerificationRequired(uid, username, result.verificationUrl)
                        twoFactorVerified -> LoginUiState.TwoFactorFailed(uid, username, "verification_rejected")
                        else -> LoginUiState.TwoFactorHumanVerificationRequired(uid, username, result.verificationUrl)
                    }
                is LoginResult.TwoFactorRequired -> {
                    // A session that is already past 2FA cannot be asked for it
                    // again — Proton's state diverged from ours; the user re-starts.
                    LoginUiState.TwoFactorFailed(uid, username, "unexpected_state")
                }
                is LoginResult.SecondPasswordRequired -> {
                    onSecondPassword = true
                    LoginUiState.SecondPasswordRequired(result.uid, result.username)
                }
            }
        }
    }

    /** Two-password mode: the second password opens the keys; a wrong one stays on this step. */
    fun submitSecondPassword(password: CharArray) {
        val (uid, username) = when (val current = _uiState.value) {
            is LoginUiState.SecondPasswordRequired -> current.uid to current.username
            is LoginUiState.SecondPasswordFailed -> current.uid to current.username
            else -> {
                password.fill(' ')
                return
            }
        }
        _uiState.value = LoginUiState.SecondPasswordSubmitting(uid, username)
        pendingJob = viewModelScope.launch {
            val result = withContext(workDispatcher) { submitSecondPassword.invoke(password) }
            _uiState.value = when (result) {
                is LoginResult.Success -> LoginUiState.Success(result.uid, result.username)
                is LoginResult.Failed -> LoginUiState.SecondPasswordFailed(uid, username, result.reason)
                is LoginResult.HumanVerificationRequired ->
                    LoginUiState.KeyDerivationHumanVerificationRequired(uid, username, result.verificationUrl)
                is LoginResult.SecondPasswordRequired -> LoginUiState.SecondPasswordRequired(uid, username)
                is LoginResult.TwoFactorRequired -> LoginUiState.SecondPasswordFailed(uid, username, "unexpected_state")
            }
        }
    }

    fun reset() {
        pendingJob?.cancel()
        pendingJob = null
        zeroizePendingPassword()
        twoFactorVerified = false
        onSecondPassword = false
        abortLogin()
        _uiState.value = LoginUiState.Idle
    }

    /**
     * The screen went away for good (system back, the task finished): the orchestrator wipes
     * the half session and its stash unless the login completed, exactly as [reset] does.
     */
    override fun onCleared() {
        pendingJob?.cancel()
        pendingJob = null
        zeroizePendingPassword()
        abortLogin()
        super.onCleared()
    }

    class Factory(
        private val attemptLogin: suspend (String, CharArray) -> LoginResult,
        private val submitTotp: suspend (String) -> LoginResult,
        private val retryKeyDerivation: suspend () -> LoginResult,
        private val abortLogin: () -> Unit,
        private val submitSecondPassword: suspend (CharArray) -> LoginResult
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            LoginViewModel(attemptLogin, submitTotp, retryKeyDerivation, abortLogin, submitSecondPassword) as T
    }
}
