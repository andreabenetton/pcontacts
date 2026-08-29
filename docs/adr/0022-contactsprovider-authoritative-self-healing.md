<!--
  SPDX-License-Identifier: GPL-3.0-only
  SPDX-FileCopyrightText: 2026 pcontacts contributors
-->

# ADR-0022: ContactsProvider is authoritative — sync metadata self-heals

- **Status:** Accepted
- **Date:** 2026-08-29
- **Deciders:** project owner
- **Related:** ADR-0008 (Room mapping), ADR-0010 (ContactsContract write strategy), ADR-0017 (bidirectional sync policies)

## Context

A field report (Mudita Kompakt) showed previously-synced contacts
vanishing from the Contacts app and never coming back, and one Proton
contact eventually appearing three times. The pull engine's two skip
tiers (server `ModifyTime`, then content hash) consulted only the Room
mapping — a stored mapping with an up-to-date `ModifyTime` and hash
suppressed the fetch *and* the ContactsContract write even when the
RawContact no longer existed. Any app holding `WRITE_CONTACTS` can pass
`caller_is_syncadapter=true` (it is a URI parameter, not a permission)
and purge our rows outright; a duplicate-cleanup pass is the suspected
trigger. Separately, `RawContactReader` collapsed the provider state to
`Map<SOURCE_ID, _ID>`, which cannot represent — and therefore silently
hid — several of our rows sharing one SOURCE_ID.

## Decision

**Room stores synchronization metadata, but ContactsProvider is
authoritative for the existence and identity of local RawContacts.
Synchronization metadata must self-heal when the two diverge.**

Concretely, on every pull:

1. **No skip without a row.** Neither the `ModifyTime` cheap-skip nor
   the content-hash skip may fire unless the provider still holds a row
   for the contact's SOURCE_ID. A missing row falls through to fetch →
   decrypt → recreate, even when the server contact is unchanged. A
   matching hash means "the data hasn't changed", not "the local row
   still exists".
2. **Tombstones count as present.** A row with `DELETED=1` is a pending
   user deletion, not a missing row — it is never recreated. When the
   tombstone itself was purged externally, a non-quarantined outbox
   DELETE for the contact still blocks recovery until the deletion
   workflow resolves (push succeeds, or the entry is discarded).
3. **Stale mapping repair.** When the provider holds the contact under
   a different `_ID` than `ContactMapEntity.androidRawContactId`, the
   mapping is repaired to the live row without rewriting contact data.
4. **Duplicate reconciliation.** Several RawContacts sharing one
   SOURCE_ID under the pcontacts account is an invalid state. The
   engine keeps a deterministic survivor — the mapped row when it is a
   live candidate, else the lowest live `_ID` — and deletes the extras
   by `_ID` through the sync-adapter URI. That delete purges the row
   without leaving `DIRTY`/`DELETED` state, so the cleanup is never
   interpreted as a user deletion and never queues a Proton DELETE.
   Reconciliation is keyed on account ownership + SOURCE_ID only; rows
   of other accounts (Google, WhatsApp, SIM, local, …) are never read,
   merged, or deleted, and no name/phone/email similarity matching is
   involved.

`RawContactReader` exposes the full provider state
(`ExistingRawContacts`: per-SOURCE_ID row lists with the `DELETED`
flag) instead of a lossy flat map.

## Consequences

- The incremental-sync cost model is unchanged: one metadata listing
  plus one provider query per run; skips still avoid per-contact
  fetches and decrypts.
- Recovery and reconciliation are idempotent — a second run after a
  repair performs zero writes.
- Recovery events are logged redacted (id hash tags and row ids only,
  never contact content).
