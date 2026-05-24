// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.app.sync

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class SyncSchedulerTest {

    private lateinit var context: Context

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val config = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.DEBUG)
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
    }

    @Test fun schedulePeriodic_enqueues_work() {
        SyncScheduler.schedulePeriodic(context)

        val wm = WorkManager.getInstance(context)
        val infos = wm.getWorkInfosForUniqueWork(PeriodicSyncWorker.UNIQUE_NAME).get()

        assertEquals("exactly one work request should be enqueued", 1, infos.size)
        assertEquals(WorkInfo.State.ENQUEUED, infos[0].state)
    }

    @Test fun cancelPeriodic_removes_enqueued_work() {
        SyncScheduler.schedulePeriodic(context)
        SyncScheduler.cancelPeriodic(context)

        val wm = WorkManager.getInstance(context)
        val infos = wm.getWorkInfosForUniqueWork(PeriodicSyncWorker.UNIQUE_NAME).get()

        assertTrue(
            "work should be cancelled",
            infos.isEmpty() || infos.all { it.state == WorkInfo.State.CANCELLED }
        )
    }

    @Test fun schedulePeriodic_twice_keeps_one_work_request() {
        SyncScheduler.schedulePeriodic(context)
        SyncScheduler.schedulePeriodic(context)

        val wm = WorkManager.getInstance(context)
        val infos = wm.getWorkInfosForUniqueWork(PeriodicSyncWorker.UNIQUE_NAME).get()

        assertEquals("KEEP policy should prevent duplicate", 1, infos.size)
    }
}
