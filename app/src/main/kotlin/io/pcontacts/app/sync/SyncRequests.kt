// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.app.sync

import android.accounts.Account
import android.content.ContentResolver
import android.content.Context
import android.os.Bundle
import android.provider.ContactsContract
import io.pcontacts.core.storage.SharedPreferencesUserPreferences

/**
 * Every sync this app asks Android for. Each request is time-stamped:
 * Android's "sync pending" flag can stay set long after the request is
 * gone, and [SyncRunningMonitor] trusts it only shortly after one
 * (issue #70).
 *
 * Requests the user did not ask for by name — the periodic worker, a
 * permission grant, the return from a verification page — go through
 * [requestIfEnabled]. They run promptly (`EXPEDITED`) but never
 * `MANUAL`: that flag means `IGNORE_SETTINGS`, and ADR-0004 promises
 * that the user's Android sync switch is honoured. Both the master
 * switch and the account's Contacts switch are checked first; the
 * framework would drop the request anyway, the check makes the
 * behaviour explicit and testable.
 *
 * Only explicit actions — "Sync now", sign-in, a linked import — go
 * through [requestNow] and carry `SYNC_EXTRAS_MANUAL`.
 */
object SyncRequests {

    /** Requests an expedited sync for [account] if Android sync is on for it; false when it is off. */
    fun requestIfEnabled(context: Context, account: Account): Boolean {
        val enabled = ContentResolver.getMasterSyncAutomatically() &&
            ContentResolver.getSyncAutomatically(account, ContactsContract.AUTHORITY)
        if (!enabled) return false
        request(context, account, manual = false)
        return true
    }

    /** A sync the user asked for: expedited and manual, so it runs whatever the switches say. */
    fun requestNow(context: Context, account: Account) = request(context, account, manual = true)

    private fun request(context: Context, account: Account, manual: Boolean) {
        SharedPreferencesUserPreferences(context.applicationContext).syncRequestedAtMillis = System.currentTimeMillis()
        val extras = Bundle().apply {
            putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
            if (manual) putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
        }
        ContentResolver.requestSync(account, ContactsContract.AUTHORITY, extras)
    }
}
