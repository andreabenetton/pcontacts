// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onSignedOut: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val verificationStats by viewModel.verificationStats.collectAsStateWithLifecycle()
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

        verificationStats?.let { stats ->
            if (stats.unverifiedContacts > 0) {
                Spacer(Modifier.height(16.dp))
                VerificationWarningBanner(stats)
            }
        }
    }
}

@Composable
private fun VerificationWarningBanner(stats: VerificationStats) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "!",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onErrorContainer
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                text = "${stats.unverifiedContacts} of ${stats.totalContacts} contacts could not be verified",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                text = "Signature verification failed. These contacts may have been tampered with.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}
