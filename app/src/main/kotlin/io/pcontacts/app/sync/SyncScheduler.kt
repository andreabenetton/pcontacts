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

    fun schedulePeriodic(context: Context, periodHours: Long = DEFAULT_PERIOD_HOURS) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()
        val request = PeriodicWorkRequestBuilder<PeriodicSyncWorker>(periodHours, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PeriodicSyncWorker.UNIQUE_NAME,
            // KEEP on first install; REPLACE when interval changes
            // (reschedule picks up user preference from caller).
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    fun reschedule(context: Context, periodHours: Long) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()
        val request = PeriodicWorkRequestBuilder<PeriodicSyncWorker>(periodHours, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PeriodicSyncWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun cancelPeriodic(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PeriodicSyncWorker.UNIQUE_NAME)
    }

    private const val DEFAULT_PERIOD_HOURS = 12L
}
