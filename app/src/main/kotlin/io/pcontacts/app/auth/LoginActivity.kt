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
import io.pcontacts.app.verification.HumanVerificationLauncher
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

    // Set when we launch the captcha Custom Tab and consumed on the
    // next onResume(). Mirrors MainActivity's same-name field — when
    // the user returns from verify.proton.me we re-invoke /auth so
    // Proton's server picks up the verification cookies set by the page.
    private var pendingVerificationReturn = false

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
                            onSuccess = { uid, username -> finishWithAccount(uid, username) },
                            onCancel = { viewModel.reset() }
                        )
                        else -> LoginScreen(
                            viewModel = viewModel,
                            onSuccess = { uid, username -> finishWithAccount(uid, username) },
                            onTwoFactorRequired = { /* handled by state-driven branch */ },
                            onHumanVerificationRequired = { url -> launchHumanVerification(url) }
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (pendingVerificationReturn) {
            pendingVerificationReturn = false
            viewModel.retryAfterVerification()
        }
    }

    private fun launchHumanVerification(url: String?) {
        if (url == null) {
            // [U] 9001 without a captcha Details block — recovery-email/SMS
            // path. Without a captcha URL we can't drive the flow from the
            // Custom Tab; surface as a Failed state so the user can retry
            // after verifying on Proton's web UI in their own browser.
            viewModel.reset()
            return
        }
        pendingVerificationReturn = true
        HumanVerificationLauncher.launch(this, url)
    }

    override fun finish() {
        if (response != null) {
            response?.onError(AccountManager.ERROR_CODE_CANCELED, "Login cancelled")
            response = null
        }
        super.finish()
    }

    private fun finishWithAccount(uid: String, username: String) {
        val accountManager = AccountManager.get(this)
        val account = Account(username, PROTON_ACCOUNT_TYPE)
        accountManager.addAccountExplicitly(account, /* password = */ null, /* userdata = */ null)
        accountManager.setUserData(account, "proton_uid", uid)

        ContentResolver.setIsSyncable(account, ContactsContract.AUTHORITY, 1)
        ContentResolver.setSyncAutomatically(account, ContactsContract.AUTHORITY, true)

        response?.onResult(
            Bundle().apply {
                putString(AccountManager.KEY_ACCOUNT_NAME, username)
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
