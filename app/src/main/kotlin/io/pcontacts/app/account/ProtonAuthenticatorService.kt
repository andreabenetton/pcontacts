// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.app.account

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Hosts the `ProtonAccountAuthenticator` for `AccountManager`. The
 * manifest declares this service with an intent filter for
 * `android.accounts.AccountAuthenticator` and a meta-data pointing to
 * `res/xml/authenticator.xml`, which is how the system discovers and
 * registers our account type.
 */
class ProtonAuthenticatorService : Service() {

    private lateinit var authenticator: ProtonAccountAuthenticator

    override fun onCreate() {
        super.onCreate()
        authenticator = ProtonAccountAuthenticator(this)
    }

    override fun onBind(intent: Intent): IBinder? =
        if (intent.action == android.accounts.AccountManager.ACTION_AUTHENTICATOR_INTENT) {
            authenticator.iBinder
        } else {
            null
        }
}
