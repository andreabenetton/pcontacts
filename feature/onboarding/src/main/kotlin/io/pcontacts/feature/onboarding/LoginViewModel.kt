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
    private val workDispatcher: CoroutineDispatcher = Dispatchers.Default
) : ViewModel() {

    private val _uiState = MutableStateFlow<LoginUiState>(LoginUiState.Idle)
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    private var pendingJob: Job? = null

    fun login(username: String, password: CharArray) {
        if (_uiState.value is LoginUiState.Submitting) return

        _uiState.value = LoginUiState.Submitting
        pendingJob = viewModelScope.launch {
            val result = withContext(workDispatcher) { attemptLogin(username, password) }
            _uiState.value = when (result) {
                is LoginResult.Success -> LoginUiState.Success(result.uid, result.username)
                is LoginResult.TwoFactorRequired -> LoginUiState.TwoFactorRequired(result.uid, result.username)
                is LoginResult.Failed -> LoginUiState.Failed(result.reason)
            }
        }
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
                is LoginResult.TwoFactorRequired -> {
                    LoginUiState.TwoFactorFailed(uid, username, "unexpected_state")
                }
            }
        }
    }

    fun reset() {
        pendingJob?.cancel()
        pendingJob = null
        _uiState.value = LoginUiState.Idle
    }

    class Factory(
        private val attemptLogin: suspend (String, CharArray) -> LoginResult,
        private val submitTotp: suspend (String) -> LoginResult
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            LoginViewModel(attemptLogin, submitTotp) as T
    }
}
