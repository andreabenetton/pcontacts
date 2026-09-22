// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.app.notifications

import android.Manifest
import android.accounts.Account
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.pcontacts.app.MainActivity
import io.pcontacts.app.R
import io.pcontacts.app.settings.DependenciesActivity

class SyncNotifier(private val context: Context) {

    /**
     * A sign-in is the one thing the user must do for sync to continue, so it goes out as a
     * heads-up alert (high-importance channel), once: re-posts by later failed syncs refresh
     * the notification silently. [storageUpgrade]: the sign-in is owed to the 2.0 secret-store
     * upgrade, not an expired session.
     */
    fun notifyReauthRequired(@Suppress("UNUSED_PARAMETER") account: Account, storageUpgrade: Boolean = false) {
        post(
            id = NOTIFICATION_ID_REAUTH,
            title = R.string.notification_reauth_title,
            text = if (storageUpgrade) {
                R.string.notification_reauth_storage_upgrade_text
            } else {
                R.string.notification_reauth_text
            },
            headsUp = true
        )
    }

    fun notifyHumanVerification(
        @Suppress("UNUSED_PARAMETER") account: Account,
        verificationUrl: String?
    ) {
        val intent = mainActivityIntent().apply {
            putExtra(EXTRA_VERIFICATION_NEEDED, true)
            if (verificationUrl != null) {
                putExtra(EXTRA_VERIFICATION_URL, verificationUrl)
            }
        }
        post(
            id = NOTIFICATION_ID_VERIFICATION,
            title = R.string.notification_verification_title,
            text = R.string.notification_verification_text,
            intent = intent
        )
    }

    /** The shipped dependency audit lists an open CVE (ADR-0024); tapping opens the Dependencies screen. */
    fun notifyOpenVulnerability() {
        post(
            id = NOTIFICATION_ID_VULNERABILITY,
            title = R.string.notification_vulnerability_title,
            text = R.string.notification_vulnerability_text,
            intent = Intent(context, DependenciesActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        )
    }

    /** The opt-in runtime check found advisories the snapshot does not list (ADR-0025). */
    fun notifyNewAdvisories(count: Int) {
        post(
            id = NOTIFICATION_ID_ADVISORY,
            title = R.string.notification_advisory_title,
            text = R.string.notification_advisory_text,
            formatArg = count,
            intent = Intent(context, DependenciesActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        )
    }

    fun notifyPersistentFailure(
        @Suppress("UNUSED_PARAMETER") account: Account,
        @Suppress("UNUSED_PARAMETER") reason: String
    ) {
        post(
            id = NOTIFICATION_ID_FAILURE,
            title = R.string.notification_sync_failure_title,
            text = R.string.notification_sync_failure_text
        )
    }

    @Suppress("LongParameterList") // one parameter per notification facet
    private fun post(
        id: Int,
        title: Int,
        text: Int,
        intent: Intent? = null,
        headsUp: Boolean = false,
        formatArg: Any? = null
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val target = intent ?: mainActivityIntent()
        val pendingIntent = PendingIntent.getActivity(
            context,
            id,
            target,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val channel = if (headsUp) NotificationChannels.SIGN_IN_REQUIRED else NotificationChannels.ACTION_REQUIRED
        val body = if (formatArg == null) context.getString(text) else context.getString(text, formatArg)
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(if (headsUp) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setOnlyAlertOnce(headsUp)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    private fun mainActivityIntent(): Intent =
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

    companion object {
        const val EXTRA_VERIFICATION_URL = "io.pcontacts.EXTRA_VERIFICATION_URL"
        const val EXTRA_VERIFICATION_NEEDED = "io.pcontacts.EXTRA_VERIFICATION_NEEDED"
        private const val NOTIFICATION_ID_REAUTH = 9002
        private const val NOTIFICATION_ID_VERIFICATION = 9001
        private const val NOTIFICATION_ID_FAILURE = 9003
        private const val NOTIFICATION_ID_VULNERABILITY = 9004
        private const val NOTIFICATION_ID_ADVISORY = 9005
    }
}
