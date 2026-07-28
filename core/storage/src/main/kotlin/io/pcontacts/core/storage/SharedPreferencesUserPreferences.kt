// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.storage

import android.content.Context

/**
 * [UserPreferences] backed by plain (non-encrypted) SharedPreferences.
 * These values are not secret — sync interval, UI preferences, etc.
 */
class SharedPreferencesUserPreferences(context: Context) : UserPreferences {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override var syncIntervalHours: Long
        get() = prefs.getLong(KEY_SYNC_INTERVAL, UserPreferences.DEFAULT_SYNC_INTERVAL_HOURS)
        set(value) {
            require(value in UserPreferences.ALLOWED_INTERVALS_HOURS) {
                "syncIntervalHours must be one of ${UserPreferences.ALLOWED_INTERVALS_HOURS}"
            }
            prefs.edit().putLong(KEY_SYNC_INTERVAL, value).apply()
        }

    override var notificationPermissionRequested: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, false)
        set(value) { prefs.edit().putBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, value).apply() }

    override var contactsPermissionRequested: Boolean
        get() = prefs.getBoolean(KEY_CONTACTS_PERMISSION_REQUESTED, false)
        set(value) { prefs.edit().putBoolean(KEY_CONTACTS_PERMISSION_REQUESTED, value).apply() }

    override var lastSyncSuccessAtMillis: Long
        get() = prefs.getLong(KEY_LAST_SYNC_SUCCESS_AT, 0L)
        set(value) { prefs.edit().putLong(KEY_LAST_SYNC_SUCCESS_AT, value).apply() }

    override var lastSyncErrorCode: String?
        get() = prefs.getString(KEY_LAST_SYNC_ERROR_CODE, null)
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_LAST_SYNC_ERROR_CODE) else putString(KEY_LAST_SYNC_ERROR_CODE, value)
            }.apply()
        }

    override var lastSyncFailedContacts: Int
        get() = prefs.getInt(KEY_LAST_SYNC_FAILED_CONTACTS, 0)
        set(value) { prefs.edit().putInt(KEY_LAST_SYNC_FAILED_CONTACTS, value).apply() }

    private companion object {
        const val PREFS_NAME = "pcontacts_user_prefs"
        const val KEY_SYNC_INTERVAL = "sync_interval_hours"
        const val KEY_NOTIFICATION_PERMISSION_REQUESTED = "notification_permission_requested"
        const val KEY_CONTACTS_PERMISSION_REQUESTED = "contacts_permission_requested"
        const val KEY_LAST_SYNC_SUCCESS_AT = "last_sync_success_at"
        const val KEY_LAST_SYNC_ERROR_CODE = "last_sync_error_code"
        const val KEY_LAST_SYNC_FAILED_CONTACTS = "last_sync_failed_contacts"
    }
}
