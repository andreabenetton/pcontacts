// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onSignedOut: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val busy = state is SettingsUiState.Syncing || state is SettingsUiState.SigningOut

    Column(
        modifier = modifier.fillMaxSize().padding(PaddingValues(24.dp)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Proton Contacts",
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(Modifier.height(24.dp))

        Button(
            enabled = !busy,
            onClick = viewModel::triggerSyncNow,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Sync now")
        }
        Spacer(Modifier.height(12.dp))

        OutlinedButton(
            enabled = !busy,
            onClick = viewModel::triggerSignOut,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Sign out")
        }

        Spacer(Modifier.height(24.dp))

        when (val s = state) {
            SettingsUiState.Idle -> Unit
            SettingsUiState.Syncing, SettingsUiState.SigningOut -> {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                CircularProgressIndicator()
            }
            is SettingsUiState.SyncDone -> Text(
                text = s.message,
                style = MaterialTheme.typography.bodyMedium
            )
            is SettingsUiState.SyncFailed -> Text(
                text = "Sync failed: ${s.reason}",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
            SettingsUiState.SignedOut -> {
                Text("Signed out.", style = MaterialTheme.typography.bodyMedium)
                LaunchedEffect(Unit) { onSignedOut() }
            }
            is SettingsUiState.SignOutFailed -> Text(
                text = "Sign-out reported errors: ${s.reason}",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
