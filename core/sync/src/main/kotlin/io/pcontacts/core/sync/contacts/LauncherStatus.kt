// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.sync.contacts

data class LauncherStatus(
    val totalContacts: Int,
    val unverifiedContacts: Int,
    val lastSyncedAtMillis: Long?,
    val pendingChanges: Int = 0,
    val quarantinedChanges: Int = 0,
    /** True when the most recent sync attempt failed (see [lastSyncErrorCode]). */
    val lastSyncFailed: Boolean = false,
    /** Stable, non-sensitive code for the last sync failure, or null. */
    val lastSyncErrorCode: String? = null,
    /** Contacts the last sync skipped (fetch/decrypt/parse failures). */
    val failedContacts: Int = 0
)
