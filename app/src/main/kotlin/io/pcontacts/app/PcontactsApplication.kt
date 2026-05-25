// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.app

import android.app.Application
import io.pcontacts.app.notifications.NotificationChannels
import io.pcontacts.app.sync.SyncScheduler
import io.pcontacts.core.storage.SharedPreferencesUserPreferences

class PcontactsApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        NotificationChannels.createAll(this)
        val prefs = SharedPreferencesUserPreferences(this)
        SyncScheduler.schedulePeriodic(this, prefs.syncIntervalHours)
    }
}
