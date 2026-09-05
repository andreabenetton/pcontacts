// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.app.sync

import android.accounts.Account
import android.content.ContentResolver
import android.provider.ContactsContract

/**
 * Watches the system sync framework and reports whether a sync for
 * [authority] is pending or active for the account supplied by
 * [account]. [onChange] fires on every framework status event (and
 * once on [start]) and may be invoked on a binder thread.
 *
 * Call [start] in onResume and [stop] in onPause.
 */
internal class SyncRunningMonitor(
    private val account: () -> Account?,
    private val onChange: (Boolean) -> Unit,
    private val authority: String = ContactsContract.AUTHORITY
) {
    private var handle: Any? = null

    fun start() {
        publish()
        if (handle != null) return
        val mask = ContentResolver.SYNC_OBSERVER_TYPE_ACTIVE or
            ContentResolver.SYNC_OBSERVER_TYPE_PENDING
        handle = ContentResolver.addStatusChangeListener(mask) { _ -> publish() }
    }

    fun stop() {
        handle?.let { ContentResolver.removeStatusChangeListener(it) }
        handle = null
    }

    private fun publish() {
        val current = account()
        if (current == null) {
            onChange(false)
            return
        }
        val running = ContentResolver.isSyncActive(current, authority) ||
            ContentResolver.isSyncPending(current, authority)
        onChange(running)
    }
}
