// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.app.account

import android.accounts.AbstractAccountAuthenticator
import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.accounts.AccountManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import io.pcontacts.app.auth.LoginActivity

/**
 * System integration per ADR-0004. Registers a "Proton Contacts" account
 * type so the user can add it from Settings → Accounts and so our
 * RawContacts can claim `ACCOUNT_TYPE = PROTON_ACCOUNT_TYPE`.
 *
 * For now `addAccount` returns an Intent that opens MainActivity as a
 * placeholder — the real LoginActivity (with SRP + 2FA) lives in
 * `:feature:onboarding` and replaces this Intent target in a later commit.
 * Auth-token retrieval is also stubbed; it will hand back the cached
 * AccessToken once `:core:storage` is in place (ADR-0009).
 */
class ProtonAccountAuthenticator(
    private val context: Context
) : AbstractAccountAuthenticator(context) {

    override fun editProperties(
        response: AccountAuthenticatorResponse?,
        accountType: String?
    ): Bundle {
        // No editable properties.
        return Bundle()
    }

    override fun addAccount(
        response: AccountAuthenticatorResponse,
        accountType: String,
        authTokenType: String?,
        requiredFeatures: Array<out String>?,
        options: Bundle?
    ): Bundle {
        val intent = Intent(context, LoginActivity::class.java).apply {
            putExtra(AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE, response)
            putExtra(EXTRA_ACCOUNT_TYPE, accountType)
            putExtra(EXTRA_AUTH_TOKEN_TYPE, authTokenType)
        }
        return Bundle().apply {
            putParcelable(AccountManager.KEY_INTENT, intent)
        }
    }

    override fun confirmCredentials(
        response: AccountAuthenticatorResponse?,
        account: Account?,
        options: Bundle?
    ): Bundle? {
        // Credential confirmation handled by the login flow once it ships.
        return null
    }

    override fun getAuthToken(
        response: AccountAuthenticatorResponse?,
        account: Account?,
        authTokenType: String?,
        options: Bundle?
    ): Bundle {
        // Until :core:storage exposes the SecretStore, signal that auth is
        // required so consumers don't believe a token is available.
        return Bundle().apply {
            putString(AccountManager.KEY_ERROR_MESSAGE, "auth required")
        }
    }

    override fun getAuthTokenLabel(authTokenType: String): String =
        "Proton Access Token"

    override fun updateCredentials(
        response: AccountAuthenticatorResponse?,
        account: Account?,
        authTokenType: String?,
        options: Bundle?
    ): Bundle? = null

    override fun hasFeatures(
        response: AccountAuthenticatorResponse?,
        account: Account?,
        features: Array<out String>?
    ): Bundle {
        // No optional features advertised yet.
        return Bundle().apply { putBoolean(AccountManager.KEY_BOOLEAN_RESULT, false) }
    }

    companion object {
        const val EXTRA_ACCOUNT_TYPE: String = "io.pcontacts.account.ACCOUNT_TYPE"
        const val EXTRA_AUTH_TOKEN_TYPE: String = "io.pcontacts.account.AUTH_TOKEN_TYPE"
    }
}
