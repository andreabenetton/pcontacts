// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.app

import android.app.Application
import io.pcontacts.app.sync.SyncScheduler
import io.pcontacts.core.storage.SharedPreferencesUserPreferences

class PcontactsApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val prefs = SharedPreferencesUserPreferences(this)
        // Schedule the periodic sync worker unconditionally; the worker
        // itself no-ops if no Proton account is present (plan §3.5).
        // KEEP policy → idempotent across app starts.
        SyncScheduler.schedulePeriodic(this, prefs.syncIntervalHours)
    }
}
