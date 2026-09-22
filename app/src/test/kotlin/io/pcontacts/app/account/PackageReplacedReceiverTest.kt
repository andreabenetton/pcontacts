// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.app.account

import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import android.app.Application
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import io.pcontacts.app.notifications.NotificationChannels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class PackageReplacedReceiverTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val account = Account("user@proton.me", PROTON_ACCOUNT_TYPE)

    @Before fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        NotificationChannels.createAll(app)
    }

    @Test fun an_upgraded_install_with_an_account_gets_the_heads_up_sign_in_notice() {
        AccountManager.get(app).addAccountExplicitly(account, null, null)

        assertTrue(UpgradeNotice.postIfPending(app, upgradePending = { true }))

        val posted = shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications.single()
        assertEquals(NotificationChannels.SIGN_IN_REQUIRED, posted.channelId)
        assertTrue(posted.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString().contains("2.0"))
    }

    @Test fun nothing_is_posted_without_an_account_or_without_a_pending_upgrade() {
        assertFalse(UpgradeNotice.postIfPending(app, upgradePending = { true }))

        AccountManager.get(app).addAccountExplicitly(account, null, null)
        assertFalse(UpgradeNotice.postIfPending(app, upgradePending = { false }))

        assertTrue(shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications.isEmpty())
    }
}
