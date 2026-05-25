// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.app.verification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import io.pcontacts.app.MainActivity
import io.pcontacts.app.R

/**
 * Posts a notification when the sync adapter receives a 9001
 * human-verification challenge. Tapping the notification launches
 * [MainActivity] with [EXTRA_VERIFICATION_URL] so it can open a
 * Chrome Custom Tab (when URL is available) or show a fallback
 * dialog (when null).
 */
object VerificationNotifier {

    const val EXTRA_VERIFICATION_URL = "io.pcontacts.EXTRA_VERIFICATION_URL"
    const val EXTRA_VERIFICATION_NEEDED = "io.pcontacts.EXTRA_VERIFICATION_NEEDED"
    private const val CHANNEL_ID = "sync_status"
    private const val NOTIFICATION_ID = 9001

    fun notify(context: Context, verificationUrl: String?) {
        ensureChannel(context)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_VERIFICATION_NEEDED, true)
            if (verificationUrl != null) {
                putExtra(EXTRA_VERIFICATION_URL, verificationUrl)
            }
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.notification_verification_title))
            .setContentText(context.getString(R.string.notification_verification_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.notification_channel_sync),
                    NotificationManager.IMPORTANCE_HIGH
                )
                nm.createNotificationChannel(channel)
            }
        }
    }
}
