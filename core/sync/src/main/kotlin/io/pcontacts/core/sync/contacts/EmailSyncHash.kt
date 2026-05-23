// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.sync.contacts

import io.pcontacts.core.contactswriter.ContactRow
import java.security.MessageDigest

/**
 * Content hash for the writer's MVP shape. Storing this in
 * `contact_map.content_hash` (ADR-0008) lets the next sync run skip
 * rewriting RawContacts whose underlying data hasn't changed — the
 * load-bearing piece for the §17 task-16 idempotency requirement.
 *
 * Covers every field that lands in ContactsContract:
 *   sourceId | displayName | structuredName pieces | emails | phones
 *
 * Hash format is private to the engine — bumping it invalidates every
 * existing `contact_map.content_hash`, so the first sync after a hash
 * change writes every contact once. Acceptable one-shot cost; the
 * commit landing the bump calls it out.
 *
 * Future-version note: when ADR / NOTE / ORG fields are added to
 * ContactRow, this hash MUST be extended in lockstep — a stale hash
 * function silently masks real changes.
 */
object EmailSyncHash {

    fun compute(row: ContactRow): String {
        // Pipe-separated, with structured field markers so empties at
        // adjacent positions can't be confused with a single populated one.
        val payload = buildString {
            append(row.sourceId).append('|')
            append(row.displayName).append('|')
            val sn = row.structuredName
            append(sn?.given.orEmpty()).append('/')
            append(sn?.family.orEmpty()).append('/')
            append(sn?.middle.orEmpty()).append('/')
            append(sn?.prefix.orEmpty()).append('/')
            append(sn?.suffix.orEmpty())
            append('|')
            row.emails.joinTo(this, separator = ",")
            append('|')
            row.phones.joinTo(this, separator = ",") { "${it.number};${it.type.name}" }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
