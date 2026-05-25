// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.pcontacts.core.sync.contacts.LauncherStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LauncherViewModel(
    private val hasAccount: () -> Boolean,
    private val loadStatus: suspend () -> LauncherStatus,
    private val workDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private val _uiState = MutableStateFlow<LauncherUiState>(LauncherUiState.Loading)
    val uiState: StateFlow<LauncherUiState> = _uiState.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            if (!hasAccount()) {
                _uiState.value = LauncherUiState.NoAccount
                return@launch
            }
            val status = withContext(workDispatcher) { loadStatus() }
            _uiState.value = LauncherUiState.SignedIn(status)
        }
    }

    class Factory(
        private val hasAccount: () -> Boolean,
        private val loadStatus: suspend () -> LauncherStatus
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            LauncherViewModel(hasAccount, loadStatus) as T
    }
}
