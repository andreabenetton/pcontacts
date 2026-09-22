// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.app.account

import android.accounts.Account
import android.accounts.AccountManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.pcontacts.app.notifications.SyncNotifier
import io.pcontacts.app.notifications.VulnerabilityNotice
import io.pcontacts.core.sync.AuthBootstrap

/**
 * Runs once, right after the package is updated, before any sync or app start: a 1.x install
 * whose session cannot be carried over (ADR-0009) is told to sign in again as a heads-up
 * notification, instead of finding out at the next periodic sync or the next app open. A
 * shipped audit snapshot with an open CVE (ADR-0024) is announced here too.
 *
 * Android 10+ forbids starting an activity from the background and a full-screen intent is
 * reserved for calls and alarms on Android 14+, so the heads-up notification is the most
 * visible legitimate signal available here.
 */
class PackageReplacedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        UpgradeNotice.postIfPending(context)
        VulnerabilityNotice.postIfOpen(context)
    }
}

internal object UpgradeNotice {

    /** Seams keep the receiver testable without a Keystore-backed secret store. */
    fun postIfPending(
        context: Context,
        upgradePending: (Context) -> Boolean = AuthBootstrap::storageUpgradePending,
        notify: (Account) -> Unit = { SyncNotifier(context).notifyReauthRequired(it, storageUpgrade = true) }
    ): Boolean {
        val account = AccountManager.get(context).getAccountsByType(PROTON_ACCOUNT_TYPE).firstOrNull()
            ?: return false
        if (!upgradePending(context)) return false
        notify(account)
        return true
    }
}
