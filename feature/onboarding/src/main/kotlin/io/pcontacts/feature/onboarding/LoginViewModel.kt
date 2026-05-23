// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.feature.onboarding

import io.pcontacts.core.sync.auth.LoginResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Plain ViewModel — not `androidx.lifecycle.ViewModel` — so the entire
 * class is testable as pure JVM (no Robolectric). The Activity hosts the
 * coroutine scope; tests can pass their own `TestScope`.
 *
 * `attemptLogin` is a function reference (not an interface) to keep the
 * test seam tight: a fake just supplies a lambda returning a canned
 * `LoginResult`, no mocking library required.
 */
class LoginViewModel(
    private val attemptLogin: suspend (username: String, password: CharArray) -> LoginResult,
    private val scope: CoroutineScope = MainScope(),
    /**
     * Where the orchestrator runs. Production uses `Dispatchers.Default`
     * (SRP arithmetic is CPU-bound). Tests pass the same `StandardTestDispatcher`
     * the surrounding TestScope uses so `advanceUntilIdle` actually advances
     * the orchestrator's continuation.
     */
    private val workDispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    private val _uiState = MutableStateFlow<LoginUiState>(LoginUiState.Idle)
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    private var pendingJob: Job? = null

    fun login(username: String, password: CharArray) {
        // Coalesce: a second tap while submitting is a no-op, not a queued retry.
        if (_uiState.value is LoginUiState.Submitting) return

        _uiState.value = LoginUiState.Submitting
        pendingJob = scope.launch {
            val result = withContext(workDispatcher) { attemptLogin(username, password) }
            _uiState.value = when (result) {
                is LoginResult.Success -> LoginUiState.Success(result.uid)
                is LoginResult.TwoFactorRequired -> LoginUiState.TwoFactorRequired(result.uid)
                is LoginResult.Failed -> LoginUiState.Failed(result.reason)
            }
        }
    }

    fun reset() {
        pendingJob?.cancel()
        pendingJob = null
        _uiState.value = LoginUiState.Idle
    }

    fun dispose() {
        pendingJob?.cancel()
        scope.cancel()
    }
}
