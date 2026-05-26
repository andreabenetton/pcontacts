// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.app.auth

import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.accounts.AccountManager
import android.content.ContentResolver
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.pcontacts.app.account.PROTON_ACCOUNT_TYPE
import io.pcontacts.app.ui.PcontactsTheme
import io.pcontacts.core.sync.AuthBootstrap
import io.pcontacts.feature.onboarding.LoginScreen
import io.pcontacts.feature.onboarding.LoginUiState
import io.pcontacts.feature.onboarding.LoginViewModel
import io.pcontacts.feature.onboarding.TwoFactorScreen

/**
 * AccountAuthenticator's addAccount Intent target. The system Settings →
 * Accounts → Add Account flow lands here; on success we register the
 * Android `Account` and signal completion back to AccountManager via the
 * AccountAuthenticatorResponse so the framework returns the user to
 * Settings without our process having to navigate it manually.
 *
 * Holds both LoginScreen and TwoFactorScreen behind the same
 * LoginViewModel — TOTP is a sub-state of the same flow, not a separate
 * Activity. The ViewModel survives configuration changes (rotation)
 * so in-flight SRP/2FA state is preserved.
 */
class LoginActivity : ComponentActivity() {

    private val orchestrator by lazy { AuthBootstrap.createLoginOrchestrator(this) }
    private val viewModel: LoginViewModel by viewModels {
        LoginViewModel.Factory(
            attemptLogin = orchestrator::login,
            submitTotp = orchestrator::submitTwoFactorCode
        )
    }
    private var response: AccountAuthenticatorResponse? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        response = extractAuthenticatorResponse()

        setContent {
            PcontactsTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val state by viewModel.uiState.collectAsStateWithLifecycle()
                    when (state) {
                        is LoginUiState.TwoFactorRequired,
                        is LoginUiState.TwoFactorSubmitting,
                        is LoginUiState.TwoFactorFailed -> TwoFactorScreen(
                            viewModel = viewModel,
                            onSuccess = { uid -> finishWithAccount(uid) },
                            onCancel = { viewModel.reset() }
                        )
                        else -> LoginScreen(
                            viewModel = viewModel,
                            onSuccess = { uid -> finishWithAccount(uid) },
                            onTwoFactorRequired = { /* handled by state-driven branch */ }
                        )
                    }
                }
            }
        }
    }

    override fun finish() {
        if (response != null) {
            response?.onError(AccountManager.ERROR_CODE_CANCELED, "Login cancelled")
            response = null
        }
        super.finish()
    }

    private fun finishWithAccount(uid: String) {
        val accountManager = AccountManager.get(this)
        val account = Account(uid, PROTON_ACCOUNT_TYPE)
        accountManager.addAccountExplicitly(account, /* password = */ null, /* userdata = */ null)

        ContentResolver.setIsSyncable(account, ContactsContract.AUTHORITY, 1)
        ContentResolver.setSyncAutomatically(account, ContactsContract.AUTHORITY, true)

        response?.onResult(
            Bundle().apply {
                putString(AccountManager.KEY_ACCOUNT_NAME, uid)
                putString(AccountManager.KEY_ACCOUNT_TYPE, PROTON_ACCOUNT_TYPE)
            }
        )
        response = null
        finish()
    }

    @Suppress("DEPRECATION")
    private fun extractAuthenticatorResponse(): AccountAuthenticatorResponse? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(
                AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE,
                AccountAuthenticatorResponse::class.java
            )
        } else {
            intent.getParcelableExtra(AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE)
        }
}
