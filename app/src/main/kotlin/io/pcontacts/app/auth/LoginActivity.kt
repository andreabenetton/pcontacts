// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.app.auth

import android.accounts.AccountAuthenticatorResponse
import android.accounts.AccountManager
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.pcontacts.app.account.PROTON_ACCOUNT_TYPE
import io.pcontacts.app.logging.AndroidLogcatSink
import io.pcontacts.app.ui.PcontactsTheme
import io.pcontacts.app.verification.HumanVerificationActivity
import io.pcontacts.core.logging.RedactingLogger
import io.pcontacts.core.storage.SharedPreferencesUserPreferences
import io.pcontacts.core.sync.AuthBootstrap
import io.pcontacts.feature.onboarding.LoginScreen
import io.pcontacts.feature.onboarding.LoginUiState
import io.pcontacts.feature.onboarding.LoginViewModel
import io.pcontacts.feature.onboarding.TwoFactorScreen
import io.pcontacts.feature.settings.AppTopBar

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

    private val orchestrator by lazy {
        AuthBootstrap.createLoginOrchestrator(this, logSink = AndroidLogcatSink())
    }
    private val viewModel: LoginViewModel by viewModels {
        LoginViewModel.Factory(
            attemptLogin = orchestrator::login,
            submitTotp = orchestrator::submitTwoFactorCode,
            retryKeyDerivation = orchestrator::retryKeyDerivation,
            abortLogin = orchestrator::abort
        )
    }
    private var response: AccountAuthenticatorResponse? = null

    // Receives the result of the HV WebView Activity. RESULT_OK means
    // the JS bridge wrote a verification token to SecretStore; the view
    // model then re-runs /auth (credentials phase), returns to the TOTP
    // screen for a fresh code (2FA phase) or resumes key derivation on
    // the kept session (after the code was accepted), all now carrying
    // the HV headers. Anything else (back-button, ESC) means the user
    // gave up — reset the VM to Idle instead of looping into the captcha.
    private val humanVerificationLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                viewModel.retryAfterVerification()
            } else {
                viewModel.reset()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        response = extractAuthenticatorResponse()

        setContent {
            PcontactsTheme {
                // The same shell as the Settings root, so signing in looks like the screen that follows.
                Scaffold(topBar = { AppTopBar() }) { padding ->
                    val state by viewModel.uiState.collectAsStateWithLifecycle()
                    when (state) {
                        is LoginUiState.TwoFactorRequired,
                        is LoginUiState.TwoFactorSubmitting,
                        is LoginUiState.TwoFactorHumanVerificationRequired,
                        is LoginUiState.KeyDerivationHumanVerificationRequired,
                        is LoginUiState.TwoFactorFailed -> TwoFactorScreen(
                            viewModel = viewModel,
                            onSuccess = { uid, username -> finishWithAccount(uid, username) },
                            onCancel = { viewModel.reset() },
                            onHumanVerificationRequired = { url -> launchHumanVerification(url) },
                            modifier = Modifier.padding(padding)
                        )
                        else -> LoginScreen(
                            viewModel = viewModel,
                            onSuccess = { uid, username -> finishWithAccount(uid, username) },
                            onTwoFactorRequired = { /* handled by state-driven branch */ },
                            onHumanVerificationRequired = { url -> launchHumanVerification(url) },
                            modifier = Modifier.padding(padding)
                        )
                    }
                }
            }
        }
    }

    private fun launchHumanVerification(url: String?) {
        if (url == null) {
            // [U] 9001 without a captcha Details block — recovery-email / SMS
            // / device-verification path. Without a captcha URL we can't
            // drive the flow in-app; reset so the user can retry after
            // verifying on Proton's web UI in their own browser.
            viewModel.reset()
            return
        }
        val intent = Intent(this, HumanVerificationActivity::class.java)
            .putExtra(HumanVerificationActivity.EXTRA_URL, url)
        humanVerificationLauncher.launch(intent)
    }

    override fun finish() {
        if (response != null) {
            response?.onError(AccountManager.ERROR_CODE_CANCELED, "Login cancelled")
            response = null
        }
        super.finish()
    }

    private fun finishWithAccount(uid: String, username: String) {
        // The one-time re-login after the 2.0 secret-store upgrade is done.
        SharedPreferencesUserPreferences(this).secretsStorageUpgraded = false
        ProtonAccountRegistrar.register(
            context = this,
            uid = uid,
            username = username,
            logger = RedactingLogger(tag = "Login", sink = AndroidLogcatSink())
        )
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
