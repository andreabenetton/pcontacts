// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.storage

/**
 * In-memory [UserPreferences] for tests. No persistence, no Android
 * dependency.
 */
class InMemoryUserPreferences : UserPreferences {
    override var syncIntervalHours: Long = UserPreferences.DEFAULT_SYNC_INTERVAL_HOURS
    override var notificationPermissionRequested: Boolean = false
    override var contactsPermissionRequested: Boolean = false
    override var lastSyncSuccessAtMillis: Long = 0L
    override var lastSyncErrorCode: String? = null
    override var lastSyncFailedContacts: Int = 0
    override var syncProgressDone: Int = 0
    override var syncProgressTotal: Int = 0
    override var systemContactsNoticeDismissed: Boolean = false
    override var secretsStorageUpgraded: Boolean = false

    override fun clearSyncState() {
        lastSyncSuccessAtMillis = 0L
        lastSyncErrorCode = null
        lastSyncFailedContacts = 0
        syncProgressDone = 0
        syncProgressTotal = 0
    }
}
