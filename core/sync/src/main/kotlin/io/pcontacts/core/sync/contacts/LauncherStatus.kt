// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.sync.contacts

data class LauncherStatus(
    val totalContacts: Int,
    val unverifiedContacts: Int,
    val lastSyncedAtMillis: Long?,
    val pendingChanges: Int = 0,
    val quarantinedChanges: Int = 0
)
