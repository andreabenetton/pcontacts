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

class SyncNotifier(private val context: Context) {

    fun notifyReauthRequired(@Suppress("UNUSED_PARAMETER") account: Account) {
        post(
            id = NOTIFICATION_ID_REAUTH,
            title = R.string.notification_reauth_title,
            text = R.string.notification_reauth_text
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

    private fun post(id: Int, title: Int, text: Int, intent: Intent? = null) {
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
        val notification = NotificationCompat.Builder(context, NotificationChannels.ACTION_REQUIRED)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(title))
            .setContentText(context.getString(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
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
    }
}
