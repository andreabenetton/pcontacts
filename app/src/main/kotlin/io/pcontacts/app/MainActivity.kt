// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.app

import android.accounts.AccountManager
import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import io.pcontacts.app.account.PROTON_ACCOUNT_TYPE
import io.pcontacts.app.auth.LoginActivity
import io.pcontacts.app.settings.SettingsActivity
import io.pcontacts.app.ui.PcontactsTheme
import io.pcontacts.core.sync.contacts.SyncBootstrap

class MainActivity : ComponentActivity() {

    private lateinit var viewModel: LauncherViewModel
    private var resumeTick = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(
            this,
            LauncherViewModel.Factory(
                hasAccount = ::hasProtonAccount,
                loadStatus = { SyncBootstrap.loadLauncherStatus(this@MainActivity) }
            )
        )[LauncherViewModel::class.java]

        setContent {
            PcontactsTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val state by viewModel.uiState.collectAsState()
                    var tick by remember { mutableIntStateOf(resumeTick) }

                    LaunchedEffect(tick) { viewModel.refresh() }

                    LauncherScreen(
                        state = state,
                        onSignIn = ::launchLogin,
                        onOpenSettings = ::launchSettings
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick++
        viewModel.refresh()
    }

    private fun hasProtonAccount(): Boolean =
        AccountManager.get(this).getAccountsByType(PROTON_ACCOUNT_TYPE).isNotEmpty()

    private fun launchLogin() {
        startActivity(Intent(this, LoginActivity::class.java))
    }

    private fun launchSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }
}

@Composable
internal fun LauncherScreen(
    state: LauncherUiState,
    onSignIn: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(PaddingValues(24.dp)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "pcontacts",
            style = MaterialTheme.typography.headlineLarge
        )
        Spacer(Modifier.height(8.dp))

        when (state) {
            is LauncherUiState.Loading -> {
                Text(
                    text = "Loading...",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            is LauncherUiState.NoAccount -> {
                Text(
                    text = "Sign in with your Proton account to start syncing contacts.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(24.dp))
                Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth()) {
                    Text("Sign in")
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                    Text("Settings")
                }
            }

            is LauncherUiState.SignedIn -> {
                Text(
                    text = "Signed in. Your Proton contacts sync into the system Contacts app.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(16.dp))

                val status = state.status
                Text(
                    text = stringResource(R.string.launcher_synced_contacts, status.totalContacts),
                    style = MaterialTheme.typography.bodyMedium
                )

                val lastSyncMillis = status.lastSyncedAtMillis
                val lastSyncText = if (lastSyncMillis != null) {
                    stringResource(
                        R.string.launcher_last_sync,
                        DateUtils.getRelativeTimeSpanString(
                            lastSyncMillis,
                            System.currentTimeMillis(),
                            DateUtils.MINUTE_IN_MILLIS
                        )
                    )
                } else {
                    stringResource(R.string.launcher_last_sync_never)
                }
                Text(
                    text = lastSyncText,
                    style = MaterialTheme.typography.bodyMedium
                )

                if (status.unverifiedContacts > 0) {
                    Text(
                        text = stringResource(
                            R.string.launcher_unverified_warning,
                            status.unverifiedContacts
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Spacer(Modifier.height(24.dp))
                Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                    Text("Settings (Sync Now / Sign Out)")
                }
            }
        }
    }
}
