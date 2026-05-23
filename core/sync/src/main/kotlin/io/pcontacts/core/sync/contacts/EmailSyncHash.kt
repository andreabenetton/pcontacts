// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.sync.contacts

import io.pcontacts.core.contactswriter.ContactRow
import java.security.MessageDigest

/**
 * Content hash for the email-only MVP. Storing this in
 * `contact_map.content_hash` (ADR-0008) lets the next sync run skip
 * rewriting RawContacts whose underlying data hasn't changed — the
 * load-bearing piece for the §17 task-16 idempotency requirement.
 *
 * Covers only the fields that land in ContactsContract for MVP
 * (sourceId, displayName, email). When the complete version adds
 * Phone/Address/Note/etc, this hash MUST be extended in lockstep — a
 * stale hash function silently masks real changes. The bump should
 * also be paired with a content_hash invalidation pass so existing
 * rows are rewritten on the next sync.
 */
object EmailSyncHash {

    fun compute(row: ContactRow): String {
        // Pipe is fine as a separator: contact ids are URL-safe base64,
        // emails always contain '@' (not '|'), and a literal '|' inside
        // a display name would be unusual enough that a collision is
        // not worth designing around for change detection.
        val payload = "${row.sourceId}|${row.displayName}|${row.email}"
        val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
