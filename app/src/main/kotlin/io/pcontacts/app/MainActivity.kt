// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.app

import android.accounts.AccountManager
import android.content.Intent
import android.os.Bundle
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.pcontacts.app.account.PROTON_ACCOUNT_TYPE
import io.pcontacts.app.auth.LoginActivity
import io.pcontacts.app.settings.SettingsActivity
import io.pcontacts.app.ui.PcontactsTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PcontactsTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    LauncherScreen(
                        hasAccount = ::hasProtonAccount,
                        onSignIn = ::launchLogin,
                        onOpenSettings = ::launchSettings
                    )
                }
            }
        }
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
private fun LauncherScreen(
    hasAccount: () -> Boolean,
    onSignIn: () -> Unit,
    onOpenSettings: () -> Unit
) {
    // Recompute on each entry — `hasAccount()` reads AccountManager directly,
    // which may have changed while another Activity was foregrounded.
    var accountPresent by remember { mutableStateOf(hasAccount()) }
    LaunchedEffect(Unit) { accountPresent = hasAccount() }

    Column(
        modifier = Modifier.fillMaxSize().padding(PaddingValues(24.dp)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "pcontacts",
            style = MaterialTheme.typography.headlineLarge
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (accountPresent)
                "Signed in. Your Proton contacts sync into the system Contacts app."
            else
                "Sign in with your Proton account to start syncing contacts.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(24.dp))

        if (accountPresent) {
            Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                Text("Settings (Sync Now / Sign Out)")
            }
        } else {
            Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth()) {
                Text("Sign in")
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                Text("Settings")
            }
        }
    }
}
