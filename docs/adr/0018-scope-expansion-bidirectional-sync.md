<!--
  SPDX-License-Identifier: GPL-3.0-only
  SPDX-FileCopyrightText: 2026 pcontacts contributors
-->

# ADR-0018: Scope expansion — bidirectional sync (supersedes ADR-0006)

- **Status:** Accepted (amended 2026-09-22 — the merge base is stored, sealed; photo identity added)
- **Date:** 2026-05-24
- **Deciders:** project owner
- **Related:** ADR-0006 (superseded), ADR-0017 (policies), ADR-0009 (Keystore KEK)

## Context

ADR-0006 locked the MVP at read-only, single-account sync. That
scope shipped: contacts flow Proton → Android, the crypto pipeline
works, the mapping store tracks state, and the sync engine is
tested. The rationale for the read-only cut — "every additional
surface multiplies failure modes against an unofficial API" — was
correct at the time and bought a working first release.

The project now has the infrastructure to absorb the next
capability tier. ADR-0017 locked in the nine policy decisions that
govern bidirectional sync (change detection, card topology, conflict
resolution, transport, failure handling, delete safety, architecture,
scope, and testing). With those decisions made, the read-only
constraint from ADR-0006 is the remaining gate.

This ADR opens the gate. It does not restate ADR-0017's policies
(those live there); it restates which parts of ADR-0006 survive,
which are superseded, and what new obligations apply.

## Decision

**Phase 9 scope: bidirectional contact sync, single account.**

The project moves from `supportsUploading="false"` to
`supportsUploading="true"` in `syncadapter.xml`. Local edits,
creates, and deletes push to Proton's server under the policies
locked in ADR-0017.

### What survives from ADR-0006

- **Single account.** Multi-account remains deferred. The
  `InMemorySession`, `SecretStore`, and `SyncAdapter` all assume
  one active account.
- **TOTP 2FA only.** FIDO2 remains deferred.
- **Field set.** The MVP field set (`FN`, `N`, `EMAIL`, `TEL`)
  is the write-back surface for phase 9. Extended fields (`ADR`,
  `ORG`, `NOTE`, `PHOTO`, `BDAY`, `IMPP`) write back when they
  are added to the read path — but they are not a phase 9 gate.
- **Periodic + manual sync.** The 1/6/12/24h configurable interval
  and "Sync Now" button remain the triggers. No live
  `ContentObserver`.
- **Logout wipe semantics.** Unchanged: revoke session, wipe
  `EncryptedSharedPreferences`, delete Android account (cascades
  `RawContacts`), wipe Room. The outbox (new) is also wiped.

### What ADR-0006 said that is now superseded

| ADR-0006 statement | New state |
|---|---|
| "Read-only. The SyncAdapter declares `supportsUploading="false"`." | `supportsUploading="true"`. Local changes push to Proton per ADR-0017. |
| "No on-disk cache of decrypted contacts." | The outbox stores `payload_hash` values, not decrypted content. If three-way merge requires storing the last-known server state, that blob is encrypted at rest (see Consequences). |
| "No conflict resolution code exists in MVP." | Three-way per-field merge (ADR-0017 §3) with user escalation on same-field conflicts. |
| "If a user edits a synced contact locally, those edits live on the local RawContact only." | Local edits push upstream on the next sync. |

### What is NOT in phase 9

- Multi-account.
- FIDO2 2FA.
- Group write-back (deferred to phase 9.5, per ADR-0017 §8).
- Live `ContentObserver` — changes are detected at sync time,
  not in real time.
- Encrypted offline cache (SQLCipher) — **dropped 2026-09-22**, not
  deferred: it would be an app-private copy of decrypted contacts,
  which ADR-0002's "no app-private plaintext copy" statement rules
  out, and the app has no contact-browsing UI that would need it.
  The mapping DB remains plaintext; it still holds no decrypted
  contact content. The outbox holds hashes, not payloads (see
  Consequences).

## Alternatives considered

- **Stay read-only indefinitely.** Rejected: write-back is the
  single most requested capability; the infrastructure now exists
  to support it safely.
- **Ship write-back and multi-account together.** Rejected: the
  same staged-risk rationale from ADR-0006 applies — adding
  multi-account to the bidirectional surface doubles the state
  space.
- **Ship write-back for all fields simultaneously.** Rejected:
  the write path for extended fields (`PHOTO`, `ADR`, etc.)
  carries card-topology subtleties that are best staged after the
  core `FN`/`N`/`EMAIL`/`TEL` round-trip is validated.

## Consequences

### Schema migration

Room gains an `outbox` table and `contact_map` gains
`last_known_server_payload_hash` (v2), then
`last_known_server_payload` (v3, amendment of 2026-09-22). A
`MigrationTestHelper` test is required for each migration.

### At-rest sensitivity of the outbox and the merge base

The outbox stores operation type, Proton contact ID, payload hash,
attempt count, and error metadata. It does **not** store decrypted
contact content.

The three-way merge (ADR-0017 §3) **does** store the last-known
server state per contact — `contact_map.last_known_server_payload`
— and that is decrypted contact content, a new at-rest sensitive
asset. Amended 2026-09-22; the rules are:

- The column holds only ciphertext: the canonical field set is
  sealed under the Keystore AEAD KEK (`pcontacts.kekv1`) **before**
  the row is written to Room, the same key and protection level as
  `keyPassword` (ADR-0009). Plaintext exists only on the heap during
  a push.
- Photo bytes are never part of the stored base. The base does
  record two SHA-256 digests (the server photo's bytes and the
  local photo's bytes as written to the provider) so the photo can
  be a merge input (ADR-0017 second amendment); a digest of a
  photo reveals nothing about it. The envelope version is 2; a
  version-1 blob reads as "no base".
- The blob is wiped by the logout wipe of `contact_map`, and the
  KEK deletion at logout makes any residue in SQLite journal pages
  unreadable.
- An unreadable blob (KEK rotated or absent, truncated data) reads
  as "no base", which ADR-0017 turns into a user-visible conflict —
  never into a merge against an empty base.
- The threat model (§2 asset inventory) lists the asset.

### Signing-key lifetime

The unlocked PGP signing key's lifetime extends from "sync-run
scoped (seconds)" to "sync-run plus every subsequent push attempt."
If the outbox retries a failed push hours later, the key must be
re-unlocked from `keyPassword`. The threat model (§2 asset
inventory, row "Unlocked PGP user private key") is updated.

### Module obligations

See ADR-0017 §Consequences for the per-module list. The headline
additions:

- `:core:crypto` — `signDetached`, `encryptThenSign`.
- `:core:sync` — `ContactWriteEngine`.
- `:core:storage` — outbox table + migration.
- `:app` — `SyncAdapter` calls both engines; `syncadapter.xml`
  flips `supportsUploading`.
- `:feature:settings` — outbox indicator, pending-delete
  cancellation, conflict UI.

## Validation

- ADR-0006's existing validation criteria (name+email appears, sync
  is idempotent, logout wipes cleanly, no decrypted content in
  logcat) continue to hold.
- A local edit to a contact's `TEL` field pushes to Proton and is
  visible in the web client on the next web-side refresh.
- A local create produces a new contact on Proton with a SIGNED
  card (`FN`) and an ENCRYPTED_AND_SIGNED card (remaining fields).
- A local delete enters the outbox with a 1-hour grace period;
  after the grace, the contact is removed from Proton.
- A same-field conflict (phone edited on both sides between syncs)
  surfaces a notification and does not silently discard either
  version.
- The `outbox` table contains no decrypted contact content
  (asserted by a test that inspects the schema + stored rows).
