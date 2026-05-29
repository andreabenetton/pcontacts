// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.sync.contacts

import io.pcontacts.core.contactswriter.ContactRow
import io.pcontacts.core.contactswriter.ImAccount
import io.pcontacts.core.contactswriter.PhoneEntry
import io.pcontacts.core.contactswriter.PostalAddress
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * Content hash for the writer's shape. Storing this in
 * `contact_map.content_hash` (ADR-0008) lets the next sync run skip
 * rewriting RawContacts whose underlying data hasn't changed — the
 * load-bearing piece for the §17 task-16 idempotency requirement.
 *
 * Covers every field that lands in ContactsContract:
 *   sourceId | displayName | structuredName | emails | phones |
 *   addresses | organization | notes | imAccounts | photo bytes
 *
 * Hash format is private to the engine — bumping it invalidates
 * every existing `contact_map.content_hash`, so the first sync
 * after a hash change writes every contact once. Acceptable
 * one-shot cost; the commit landing the bump calls it out.
 *
 * Implementation builds the payload as raw bytes (photo data is
 * appended verbatim rather than UTF-8-encoded text) so binary
 * fields are hashed bit-exactly.
 */
object EmailSyncHash {

    /**
     * Prepended to the hash payload so that any cross-version change
     * to what the writer EMITS (not what's in ContactRow) — for
     * example the Phase 12 chip rows added per email — invalidates
     * every existing `contact_map.content_hash` and forces a one-shot
     * rewrite of every contact on the next sync. Bump when the
     * writer's per-row op set changes.
     *
     *   v1 — original shape (RawContacts + StructuredName + Data rows).
     *   v2 — adds the Send-via-Proton-Mail chip row per email
     *        (ADR-0021).
     */
    private const val FORMAT_VERSION = "v2"

    fun compute(row: ContactRow): String {
        val sink = ByteArrayOutputStream()
        fun write(s: String) = sink.write(s.toByteArray(Charsets.UTF_8))
        fun sep() = sink.write(byteArrayOf(0x1F))   // ASCII unit separator

        write(FORMAT_VERSION); sep()
        write(row.sourceId); sep()
        write(row.displayName.orEmpty()); sep()

        val sn = row.structuredName
        write(sn?.given.orEmpty()); sep()
        write(sn?.family.orEmpty()); sep()
        write(sn?.middle.orEmpty()); sep()
        write(sn?.prefix.orEmpty()); sep()
        write(sn?.suffix.orEmpty()); sep()

        write(row.emails.joinToString(",")); sep()
        write(row.phones.joinToString(",", transform = ::phoneFingerprint)); sep()
        write(row.addresses.joinToString(",", transform = ::addressFingerprint)); sep()

        val org = row.organization
        write(org?.company.orEmpty()); sep()
        write(org?.department.orEmpty()); sep()
        write(org?.title.orEmpty()); sep()

        write(row.notes.joinToString("\n")); sep()
        write(row.imAccounts.joinToString(",", transform = ::imFingerprint)); sep()
        write(row.groupRowIds.joinToString(",")); sep()

        // Photo bytes hashed bit-exactly — any change in pixels triggers
        // a rewrite on the next sync.
        row.photo?.data?.let(sink::write)

        val digest = MessageDigest.getInstance("SHA-256").digest(sink.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun phoneFingerprint(p: PhoneEntry): String =
        "${p.number};${p.type.name};${if (p.isPrimary) 1 else 0}"

    private fun addressFingerprint(a: PostalAddress): String = listOf(
        a.poBox.orEmpty(),
        a.neighborhood.orEmpty(),
        a.street.orEmpty(),
        a.city.orEmpty(),
        a.region.orEmpty(),
        a.postcode.orEmpty(),
        a.country.orEmpty(),
        a.type.name,
        if (a.isPrimary) "1" else "0"
    ).joinToString(";")

    private fun imFingerprint(i: ImAccount): String =
        "${i.handle};${i.protocol.name};${i.customProtocol.orEmpty()};${i.type.name}"
}
