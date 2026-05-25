// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.app.notifications

import android.content.Context
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import io.pcontacts.app.R

object NotificationChannels {

    const val STATUS = "pcontacts.sync.status"
    const val ACTION_REQUIRED = "pcontacts.sync.action_required"

    fun createAll(context: Context) {
        NotificationManagerCompat.from(context).createNotificationChannelsCompat(
            listOf(
                NotificationChannelCompat.Builder(
                    STATUS,
                    NotificationManagerCompat.IMPORTANCE_LOW
                )
                    .setName(context.getString(R.string.channel_status_name))
                    .setDescription(context.getString(R.string.channel_status_description))
                    .build(),
                NotificationChannelCompat.Builder(
                    ACTION_REQUIRED,
                    NotificationManagerCompat.IMPORTANCE_DEFAULT
                )
                    .setName(context.getString(R.string.channel_action_required_name))
                    .setDescription(context.getString(R.string.channel_action_required_description))
                    .build()
            )
        )
    }
}
