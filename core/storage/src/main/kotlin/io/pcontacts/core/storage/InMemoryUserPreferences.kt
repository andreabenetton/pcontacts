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
}
