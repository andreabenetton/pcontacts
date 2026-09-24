<!--
  SPDX-License-Identifier: GPL-3.0-only
  SPDX-FileCopyrightText: 2026 pcontacts contributors
-->

# ADR-0022: ContactsProvider is authoritative — sync metadata self-heals

- **Status:** Accepted (amended 2026-09-22 — orphan mappings and cancelled deletions; amended 2026-09-24 — providers with a recycle bin)
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

## Amendment (2026-09-22): orphan mappings; cancelling a deletion

Two gaps found by the second v2.0.0 review, both strengthening the
"provider is authoritative" rule:

- **Orphan mappings.** A chunked provider batch that fails leaves the
  earlier chunks committed; the mapping of a contact one of them deleted
  outlived its row and its server contact, and nothing ever removed it.
  Now the mappings of rows a failed batch deleted are dropped at once,
  and every run drops any live mapping whose contact is on neither
  side. A queued change (a `local-<id>` create, any live outbox row)
  keeps its mapping.
- **Cancelling a deletion** is a state transition, not just the removal
  of the queued DELETE: the tombstoned RawContact is made live again
  through the sync-adapter URI (its Data rows were never touched), or,
  when the provider already purged it, the mapping is marked for a
  refetch so the next pull recreates the contact; a sync is requested
  either way.

## Amendment (2026-09-24): providers with a recycle bin

Samsung's contacts provider moves a deleted RawContact into a recycle
bin (`sec_in_trash`) and hides it from every query — including the
sync adapter's — unless it also carries `DELETED=1`, which it does not
for our account. Validated on a Samsung A40 (Android 11, One UI): a
Proton contact deleted in Samsung Contacts left no tombstone, and rule 1
recreated it at the next pull, so the user's deletion was silently
undone and never reached Proton.

On such a provider a vanished row is more likely the user's deletion
than an external purge, and the two cannot be told apart. Recreating
it silently overrides the user; deleting it on Proton silently could
lose a contact another app purged. Neither is decided by the app:

- **Detection.** The provider has a recycle bin when its `raw_contacts`
  cursor carries the `sec_in_trash` column. The check reads column
  names only, never a trashed row.
- **Ask, don't recreate.** On a provider with a recycle bin, a mapped
  contact whose row has vanished (no live row, no tombstone, no queued
  DELETE) is not recreated. Its mapping is marked as a conflict
  ("removed on this phone") and the user chooses: **delete it from
  Proton** — an ordinary queued DELETE with the grace period and its
  cancel — or **put it back** — the mapping is marked for a refetch and
  the next pull recreates the row.
- **A row that comes back** (restored from the bin) while the question
  is open clears the conflict; the contact syncs as before.
- **An explicit refetch is still honoured.** A mapping marked for a
  refetch (content hash cleared — "put it back", or a cancelled
  deletion whose tombstone was purged) is recreated without asking.
- Providers without a recycle bin are unchanged: rule 1 still
  recreates a vanished row.

This keeps the provider authoritative (the vanished row is not papered
over) while leaving the one ambiguous case to the user.
