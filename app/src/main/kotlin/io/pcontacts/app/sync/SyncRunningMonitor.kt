// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.app.sync

import android.accounts.Account
import android.content.ContentResolver
import android.content.Context
import android.net.ConnectivityManager
import android.provider.ContactsContract

/**
 * Watches the system sync framework and reports whether a sync for
 * [authority] is pending or active for the account supplied by
 * [account], and whether a pending one waits for a connection
 * ([isOnline] false). [onChange] fires on every framework status event
 * (and once on [start]) and may be invoked on a binder thread.
 *
 * "Active" is trusted as Android reports it. "Pending" counts only
 * shortly after the app's own request ([requestedAt]): Android can leave
 * the flag set with no sync to run, and the card then said "Sync in
 * progress" with "Sync now" greyed out until a reboot (issue #70).
 *
 * Call [start] in onResume and [stop] in onPause.
 */
internal class SyncRunningMonitor(
    private val account: () -> Account?,
    private val onChange: (running: Boolean, waitingForNetwork: Boolean) -> Unit,
    private val isOnline: () -> Boolean,
    private val requestedAt: () -> Long,
    private val clock: () -> Long = System::currentTimeMillis,
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
            onChange(false, false)
            return
        }
        val active = ContentResolver.isSyncActive(current, authority)
        val online = isOnline()
        val pending = trustedPending(ContentResolver.isSyncPending(current, authority), online, clock() - requestedAt())
        onChange(active || pending, waitingForNetwork(active, pending, online))
    }
}

/**
 * Android's pending flag, counted only for a while after the app requested a sync: a couple of
 * minutes with a network (an expedited request starts within seconds), an hour without one (it
 * waits for the connection). Past that the flag is stale (issue #70).
 */
internal fun trustedPending(pending: Boolean, online: Boolean, sinceRequestMs: Long): Boolean =
    pending && sinceRequestMs in 0 until if (online) PENDING_ONLINE_MS else PENDING_OFFLINE_MS

private const val PENDING_ONLINE_MS = 2 * 60 * 1000L
private const val PENDING_OFFLINE_MS = 60 * 60 * 1000L

/**
 * A requested sync that Android holds because there is no connection: pending, not started, and
 * no network for this app. Android starts it when one comes back (its job requires connectivity).
 */
internal fun waitingForNetwork(active: Boolean, pending: Boolean, online: Boolean): Boolean =
    pending && !active && !online

/**
 * Whether this app has a network to use. `getActiveNetwork` is null with no default network, and
 * also when that network is blocked for the app (seen on the Pixel, Android 17, 2026-10-02).
 */
internal fun hasNetwork(context: Context): Boolean =
    context.getSystemService(ConnectivityManager::class.java)?.activeNetwork != null
