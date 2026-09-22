// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.app.sync

import android.accounts.Account
import android.content.ContentResolver
import android.os.Bundle
import android.provider.ContactsContract

/**
 * Sync requests the user did not ask for by name — the periodic worker,
 * a permission grant, the return from a verification page. They run
 * promptly (`EXPEDITED`) but never `MANUAL`: that flag means
 * `IGNORE_SETTINGS`, and ADR-0004 promises that the user's Android
 * sync switch is honoured. Both the master switch and the account's
 * Contacts switch are checked first; the framework would drop the
 * request anyway, the check makes the behaviour explicit and testable.
 *
 * Only explicit actions — "Sync now", sign-in, a linked import — carry
 * `SYNC_EXTRAS_MANUAL`.
 */
object SyncRequests {

    /** Requests an expedited sync for [account] if Android sync is on for it; false when it is off. */
    fun requestIfEnabled(account: Account): Boolean {
        val enabled = ContentResolver.getMasterSyncAutomatically() &&
            ContentResolver.getSyncAutomatically(account, ContactsContract.AUTHORITY)
        if (!enabled) return false
        val extras = Bundle().apply { putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true) }
        ContentResolver.requestSync(account, ContactsContract.AUTHORITY, extras)
        return true
    }
}
