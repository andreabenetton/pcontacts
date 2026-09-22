// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.app.sync

import android.accounts.AccountManager
import android.content.Context
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
 * this worker just kicks the sync framework. It never sets
 * `SYNC_EXTRAS_MANUAL` and it checks the master and per-account
 * auto-sync switches first (ADR-0004): a timer is not the user, and
 * "Sync off" in Android Settings must mean off.
 */
class PeriodicSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val accounts = AccountManager.get(applicationContext)
            .getAccountsByType(PROTON_ACCOUNT_TYPE)
        // No account, or sync switched off → nothing to do. Don't retry; the
        // work-request fires again on its next cadence.
        accounts.forEach(SyncRequests::requestIfEnabled)
        return Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "io.pcontacts.periodic-sync"
    }
}
