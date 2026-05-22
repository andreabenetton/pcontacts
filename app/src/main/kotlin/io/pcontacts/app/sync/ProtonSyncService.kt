// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.app.sync

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Hosts the `ProtonSyncAdapter` for the AOSP sync framework (ADR-0004).
 * The manifest declares this service with the
 * `android.content.SyncAdapter` action and a meta-data resource pointing
 * to `res/xml/syncadapter.xml`, which is how the system associates this
 * adapter with the `ContactsContract.AUTHORITY` for our account type.
 */
class ProtonSyncService : Service() {

    private lateinit var syncAdapter: ProtonSyncAdapter

    override fun onCreate() {
        super.onCreate()
        syncAdapter = ProtonSyncAdapter(applicationContext)
    }

    override fun onBind(intent: Intent): IBinder? = syncAdapter.syncAdapterBinder
}
