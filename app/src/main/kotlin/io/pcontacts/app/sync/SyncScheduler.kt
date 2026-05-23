// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.app.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Schedules + cancels the PeriodicSyncWorker. Called from
 * PcontactsApplication.onCreate (always — KEEP policy is idempotent)
 * and from LogoutHelper / LoginActivity when account state changes.
 */
object SyncScheduler {

    /** WorkManager's documented minimum is 15 minutes; 12h fits MVP plan §5. */
    private const val PERIOD_HOURS = 12L

    fun schedulePeriodic(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()
        val request = PeriodicWorkRequestBuilder<PeriodicSyncWorker>(PERIOD_HOURS, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PeriodicSyncWorker.UNIQUE_NAME,
            // KEEP so re-scheduling on every app start doesn't reset the
            // backoff clock; UPDATE would re-arm the period from now.
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    fun cancelPeriodic(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PeriodicSyncWorker.UNIQUE_NAME)
    }
}
