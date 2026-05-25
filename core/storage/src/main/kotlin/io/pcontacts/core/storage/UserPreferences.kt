// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.storage

/**
 * Read/write surface for non-secret user preferences. Unlike
 * [SecretStore], these values are not encrypted — they hold no
 * sensitive material.
 *
 * Per CLAUDE.md all SharedPreferences access is centralised in
 * `:core:storage`; this interface is the non-secret companion to
 * [SecretStore].
 */
interface UserPreferences {

    /** Periodic sync interval in hours. Default [DEFAULT_SYNC_INTERVAL_HOURS]. */
    var syncIntervalHours: Long

    /** Whether the POST_NOTIFICATIONS runtime permission has been requested. */
    var notificationPermissionRequested: Boolean

    /** Whether READ_CONTACTS / WRITE_CONTACTS runtime permissions have been requested. */
    var contactsPermissionRequested: Boolean

    companion object {
        const val DEFAULT_SYNC_INTERVAL_HOURS = 12L
        val ALLOWED_INTERVALS_HOURS = listOf(1L, 6L, 12L, 24L)
    }
}
