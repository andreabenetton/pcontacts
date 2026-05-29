// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.sync.contacts

/**
 * Lightweight reference to a Proton contact whose signature
 * verification failed during the last sync. Returned by
 * [SyncBootstrap.listUnverifiedContacts] for the settings UI to
 * enumerate the affected contacts.
 *
 * Carries no display name on purpose: contact names live in
 * ContactsContract, not in our Room mapping (ADR-0007 keeps
 * decrypted contact content out of local persistence). The
 * settings layer resolves the name via ContentResolver at render
 * time, using [androidRawContactId] as the join key.
 */
data class UnverifiedContactRef(
    val protonContactId: String,
    val androidRawContactId: Long,
    val lastError: String?
)
