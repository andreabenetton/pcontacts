<!--
  SPDX-License-Identifier: GPL-3.0-only
  SPDX-FileCopyrightText: 2026 pcontacts contributors
-->

# ADR-0017: Bidirectional sync — scope and policies

- **Status:** Accepted
- **Date:** 2026-05-24
- **Deciders:** project owner
- **Related:** ADR-0006 (MVP read-only), ADR-0007 (client-side decrypt), ADR-0008 (Room mapping), ADR-0009 (secrets storage), ADR-0010 (ContactsContract write strategy), ADR-0014 (modulus pinning)

## Context

ADR-0006 scoped the MVP to read-only sync. Phase 9 of the roadmap
adds write-back: local edits, creates, and deletes on Android push
to Proton's server. Bidirectional sync introduces seven problems the
read path does not have:

1. **Change detection** — the system Contacts UI does not notify the
   sync adapter when a user edits a row; edits must be discovered.
2. **Encryption on write** — every outgoing change must be encrypted
   and signed under the user's PGP key (the inverse of the existing
   decrypt-and-verify path).
3. **Card-type preservation** — Proton splits a contact across up to
   four card types (`CLEAR_TEXT` / `SIGNED` / `ENCRYPTED` /
   `ENCRYPTED_AND_SIGNED`). Round-tripping requires deciding which
   fields land in which card. `[V]` The web client enforces routing
   rules; see `packages/shared/lib/contacts/encrypt.ts` in
   ProtonMail/WebClients.
4. **Conflict resolution** — a user can edit on phone and on the
   Proton web client between syncs; someone must win, with privacy
   and data-loss consequences.
5. **Concurrency and ordering** — two devices syncing at once, or a
   sync interrupted mid-batch, must not corrupt either side.
6. **Failure recovery** — a failed upload of contact #47 must not
   poison contacts #48–#200.
7. **Delete safety** — once a delete is pushed to Proton, it is
   gone; there is no server-side Trash.

This ADR locks in the policy for each problem. Choices are numbered
to match the analysis document that preceded this ADR.

## Decision

### 1. Change detection — hybrid DIRTY + hash (Choice 1C)

Use Android's `RawContacts.DIRTY` and `RawContacts.DELETED` flags
as the fast filter to identify changed contacts. For each dirty
contact, compare a content hash of the current `Data` rows against
`contact_map.content_hash` to produce the minimal change set. Store
the Proton `ModifyTime` high-watermark in `SyncState` as today.

This is the pattern DAVx5 uses. It composes with the existing
two-tier skip (`ModifyTime` then `content_hash`) on the read path
and degrades gracefully: if the DIRTY flag is unreliable on an OEM
device, a full hash-diff fallback for that account is possible
without a data-model change.

**Schema consequence:** `contact_map` must store
`last_known_server_payload_hash` (or a snapshot of the last-pushed
state) to support three-way merge. This is a Room migration.

### 2. Card encryption topology — simple layout initially, preserve on edit later (Choice 2B → 2C)

**Initially (phase 9):** every field except `FN` goes into one
`ENCRYPTED_AND_SIGNED` card; `FN` lives in a `SIGNED` card. This
is always valid — Proton's data model accepts arbitrary card splits
— but when the user views a phone-edited contact on the web, the
card layout will differ from what the web client would have
produced.

**Promotion path (documented, not scheduled):** Choice 2C —
preserve the original card topology on edit by fetching the current
server cards, decrypting them, applying the user's diff
field-by-field preserving which card each field came from, and
re-encrypting. For brand-new contacts created on the phone, fall
back to the 2B layout. This requires storing field provenance
(either in `contact_map` or by re-fetching server cards before each
push).

Choice 2A (mirror the web client's field-to-card routing rules
exactly) is rejected. It requires bug-for-bug parity with a
TypeScript codebase that changes without notice, and ADR-0006's
rationale for a read-only MVP applies equally: tracking upstream
WebClients continuously is beyond the project's maintenance budget.

**Crypto-side obligation:** `:core:crypto`'s `OpenPgpService` needs
new operations: `signDetached(plaintext, key) → signature` and
`encryptThenSign(plaintext, encryptKey, signKey) → armored`.
BouncyCastle supports both. The signing key is the same as the
decryption key (Proton stores them as one), so no new key-management
problem arises, but the unlocked-key lifetime now extends from "one
sync run" to "one sync run plus every subsequent push attempt."
This is a threat-model amendment (update `docs/THREAT_MODEL.md`).

### 3. Conflict resolution — per-field merge with user escalation (Choice 3B + 3C)

Default policy: three-way merge at the field level. Diff
`server-current` vs. `last-known-server-state` vs. `local-current`.
If server changed `TEL` and local changed `EMAIL`, both changes
survive. If both changed the same field, escalate to the user
(Choice 3C): record the conflict in `sync_state`, surface a
notification, present a "use phone version / use Proton version"
choice.

Choice 3A (last-write-wins by `ModifyTime`) is rejected — silent
data loss is unacceptable in a privacy-focused product where users
spread edits across devices.

Choice 3D (local-always-wins quiet period) is interesting but too
quirky to ship without user education; defer as a future option.

**Schema consequence:** three-way merge requires the actual
last-known server state, not just a hash. `contact_map` grows a
`last_known_server_payload` column (or a normalised snapshot table).

### 4. Transport — independent pushes with bounded parallelism (Choice 4A)

Push each contact independently via
`PUT /contacts/v4/contacts/{id}` `[V]` (updates) or
`POST /contacts/v4/contacts` `[V]` (creates). Use a
`coroutineScope` with a `Semaphore(4)` for bounded parallelism.

Choice 4B (bulk endpoint) is deferred pending research into
partial-failure semantics of Proton's bulk-create endpoint.

Choice 4C (push-then-poll confirmation) is over-engineering with no
evidence that Proton's API has non-idempotent failure modes.

### 5. Failure handling — persistent outbox (Choice 5B)

Add an `outbox` table in Room. Every detected local change appends
a row (`proton_contact_id`, `op_type`, `payload_hash`, `attempts`,
`last_error`, `next_attempt_at`). The push engine drains the outbox:
on success, removes the row; on transient failure (5xx, 429,
network), increments `attempts` and schedules the next try with
exponential backoff; on permanent failure (4xx excluding 429),
marks the row as quarantined and surfaces a notification.

The `:feature:settings` UI gains a visible outbox indicator
("3 changes pending sync, last attempt failed: rate limited") and
a per-contact retry / discard action.

**Race condition (re-edit while pending):** when push of entry #1
succeeds, compare the just-pushed `payload_hash` against the current
`RawContact` hash. If they differ, the contact is dirty again and a
new outbox entry is created. If the DIRTY flag was already set, the
next sync picks it up. No special handling needed.

Choice 5A (no persistent queue, rely on DIRTY flag alone) is
rejected — it provides no retry granularity and no visibility into
permanent failures.

Choice 5C (idempotency keys) is deferred until evidence demands it;
Proton's `PUT` endpoints appear naturally idempotent (same body →
same end state) `[A]`.

### 6. Delete safety — soft-delete with 1-hour grace (Choice 6A)

When the user deletes a contact locally, the outbox entry is created
with `op_type = DELETE` but is not pushed for 1 hour. During the
grace period:

- If the user re-creates a contact with the same `SOURCE_ID` (or
  restores from backup), the queued delete is cancelled.
- The `:feature:settings` UI shows "N contacts will be removed from
  Proton in M minutes" with a per-contact cancel action.

After the grace period expires, the next sync run pushes the delete
via `PUT /contacts/v4/contacts/delete` `[V]` (bulk-delete endpoint).

Choice 6B (confirm-on-delete notification) is rejected — friction
on every delete trains users to auto-confirm, defeating the
protection.

Choice 6C (immediate push) is rejected — no safety net for
accidental bulk-deletion. For a privacy app where users may
deliberately delete sensitive contacts, the 1-hour grace is a
reasonable middle ground.

### 7. Architecture — separate write engine (Choice 7B)

Introduce `ContactWriteEngine` in `:core:sync` as a peer to the
existing `ContactDetailSyncEngine`. `ProtonSyncAdapter.onPerformSync`
calls `ContactWriteEngine.push(account)` first, then
`ContactDetailSyncEngine.sync(account)`. Push-before-pull ensures
the subsequent pull sees the just-pushed `ModifyTime` updates.

Choice 7A (extend existing engines) is rejected — the read engine
is already at detekt's complexity threshold (audit finding M2), and
doubling its responsibility makes that worse.

Choice 7C (unified bidirectional engine) is rejected — it discards
tested code and eliminates the useful capability of read-only sync
as a distinct mode.

### 8. Scope — contacts first, groups later (Choice 8A)

Phase 9 covers contacts only. Group management stays read-only
(Proton-web-only for creates/renames/deletes). Phase 9.5, scoped
separately, adds bidirectional group sync via `ProtonLabelsApi`
write methods.

A user who renames a group locally will see the rename revert on
the next sync. The `:feature:settings` UI surfaces a warning:
"Group changes don't sync to Proton — edit groups on the web for
now."

## Alternatives considered

Each choice section above documents the rejected alternatives
inline. The major structural alternatives were:

- **Last-write-wins (3A):** rejected for silent data loss.
- **Mirror web client card topology exactly (2A):** rejected for
  unsustainable maintenance burden.
- **Extend existing read engine (7A):** rejected for complexity.
- **No persistent outbox (5A):** rejected for lack of failure
  visibility and retry granularity.
- **Immediate delete push (6C):** rejected for lack of safety net.

## Consequences

### What this makes easier

- Each policy is independently testable: the outbox, the three-way
  merger, the card serializer, and the delete grace timer are
  separate units with clear inputs and outputs.
- The read engine is untouched; read-only sync remains a working,
  tested capability.
- The 2B card topology eliminates the need to port the web client's
  field-routing rules, significantly reducing the initial scope.

### What this makes harder

- The Room schema grows substantially (outbox table,
  `last_known_server_payload` in `contact_map`). Each migration
  needs a `MigrationTestHelper` test.
- The unlocked signing-key lifetime extends beyond a single sync
  run, requiring a threat-model update.
- The 1-hour delete grace creates a window where the local UI and
  the Proton web client show different states. This must be
  documented and surfaced in the Settings UI.
- Three-way field-level merge is non-trivial to implement correctly
  and to test exhaustively (especially for multi-value properties
  like `TEL` and `EMAIL`).

### New obligations

- **`:core:crypto`** — `signDetached` and `encryptThenSign`
  operations. Captured-vector tests required (ADR-0013).
- **`:core:proton-api`** — `PUT /contacts/v4/contacts/{id}`,
  `POST /contacts/v4/contacts`,
  `PUT /contacts/v4/contacts/delete` Retrofit methods. New DTOs.
- **`:core:proton-contacts`** — `ContactSerializer` (inverse of
  `ContactDecrypter`): `DecryptedContact → List<ContactCardDto>`.
  Card-topology decision (2B) lives here.
- **`:core:contacts-writer`** — reader that produces outgoing
  `ContactRow`s from `RawContacts WHERE dirty=1`.
- **`:core:storage`** — Room migration for outbox table and expanded
  `contact_map`. `MigrationTestHelper` tests required.
- **`:core:sync`** — `ContactWriteEngine` plus its `WriteReport`.
- **`:app`** — `ProtonSyncAdapter.onPerformSync` calls both engines.
  `syncadapter.xml` flips `supportsUploading="true"`.
- **`:feature:settings`** — outbox indicator, pending-delete
  cancellation, conflict-resolution UI.
- **`docs/THREAT_MODEL.md`** — signing-key lifetime amendment.

## Validation

- **Round-trip property test:** any `DecryptedContact` that passes
  through `serialize → encrypt → decrypt → merge` equals the
  original. Use kotest-property.
- **Three-way merge unit tests:** given `(server-current,
  last-known, local-current)`, produce the expected merged result.
  Cover both disjoint-field and same-field conflicts.
- **Outbox unit tests:** exercise the queue against fake API
  failures (429, 5xx, network `IOException`). Validate retry
  scheduling and quarantine.
- **DIRTY flag instrumented test:** mutate a row outside
  `caller_is_syncadapter=true`, observe `DIRTY=1`; clear it via the
  sync URI, observe `DIRTY=0`.
- **Live round-trip test:** extend `LiveProtonLoginTest` — create a
  contact, verify it appears in the next pull, edit it, verify the
  edit lands, delete it, verify it's gone. Gated on
  `PCONTACTS_LIVE_TEST`.
- **Card topology:** on the 2C promotion, verify round-trip fidelity
  by comparing pre-edit and post-edit card layouts fetched from the
  server.
