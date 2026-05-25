// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.app

import io.pcontacts.core.sync.contacts.LauncherStatus

sealed interface LauncherUiState {
    data object Loading : LauncherUiState
    data object NoAccount : LauncherUiState
    data class SignedIn(val status: LauncherStatus) : LauncherUiState
}
