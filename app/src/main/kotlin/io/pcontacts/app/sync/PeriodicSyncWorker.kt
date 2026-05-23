// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.app.sync

import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.Context
import android.os.Bundle
import android.provider.ContactsContract
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.pcontacts.app.account.PROTON_ACCOUNT_TYPE

/**
 * Periodic belt-and-suspenders for the SyncAdapter. Plan §3.5 calls
 * out that vendor power-profile optimisations can mute system sync
 * on some devices; WorkManager fires under more conservative
 * constraints (battery-not-low, network-connected) and explicitly
 * pokes ContentResolver.requestSync.
 *
 * The actual sync work runs in `ProtonSyncAdapter.onPerformSync` —
 * this worker just kicks the sync framework.
 */
class PeriodicSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val accounts = AccountManager.get(applicationContext)
            .getAccountsByType(PROTON_ACCOUNT_TYPE)
        if (accounts.isEmpty()) {
            // No account → nothing to sync. Don't retry; the work-request
            // will fire again on its next cadence.
            return Result.success()
        }
        val extras = Bundle().apply {
            // EXPEDITED + MANUAL hints — we want the sync to run promptly
            // when this worker fires.
            putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
            putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
        }
        accounts.forEach { account ->
            ContentResolver.requestSync(account, ContactsContract.AUTHORITY, extras)
        }
        return Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "io.pcontacts.periodic-sync"
    }
}
