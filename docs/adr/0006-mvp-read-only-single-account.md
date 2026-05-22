# ADR-0006: MVP scope — read-only, single account

- **Status:** Accepted
- **Date:** 2026-05-22
- **Deciders:** project owner

## Context

The maximum feasible feature set (bidirectional sync, multi-account, FIDO2, photos, groups, conflict resolution, offline cache) is several months of work and stacks risk: every additional surface multiplies the failure modes against an unofficial API. Shipping anything useful in a reasonable time means cutting hard.

The high-value, low-risk slice is:

- One Proton account at a time.
- Read-only: Proton → Android. No writes back to Proton.
- The minimum field set that makes the system Contacts app useful: name + email + (optionally) phone.
- Manual + scheduled sync, no live observers.
- No offline cache: re-fetch every time. Decrypted vCard never persists to disk.

This slice gives a real user value (Proton contacts in the dialer, in messaging apps, in autocomplete) without any of the bidirectional write headaches, conflict-resolution UI, photo BLOB plumbing, or multi-account state machine.

## Decision

**MVP = phases 0–7 partial** (per the plan):

- Single Proton account (the user picks one; if they have multiple Proton accounts, only one is active per device).
- SRP login + TOTP 2FA. FIDO2 deferred to post-MVP.
- Fields synced: `FN`, `N` → `StructuredName`; `EMAIL` (with TYPE preserved) → `Email`; `TEL` → `Phone`.
- Sync triggers: manual "Sync Now" button + 12 h periodic via WorkManager-armed SyncAdapter.
- Read-only. The SyncAdapter declares `supportsUploading="false"`.
- No on-disk cache of decrypted contacts. We re-fetch and re-decrypt on every sync. The Room mapping store (ADR-0008) holds only IDs + `ModifyTime` + content hash, never decrypted content.
- Logout = revoke session + wipe EncryptedSharedPreferences + delete the Android account (which cascades to delete our `RawContacts`).

**Out of MVP** (deferred): photos, groups (`CATEGORIES` / `LabelIDs`), `StructuredPostal`, `Organization`, `Note`, `Event` (BDAY/ANNIVERSARY), `Website`, FIDO2 2FA, bidirectional write-back, conflict resolution, multi-account, encrypted offline cache, biometric unlock.

## Alternatives considered

- **Ship phases 0–10 in one release.** Rejected: too long until first useful artifact; every surface compounds the risk that a Proton API change breaks the entire app before it ships.
- **Read-only + full field mapping at MVP.** Rejected as a near-miss compromise: the field-mapping work is straightforward but each field exposes additional ContactsContract subtleties (photo BLOB size limits, address component ordering, group ID resolution) that are better staged after a working name+email+phone baseline is in production.
- **Two-account MVP.** Rejected: multi-account complicates the SyncAdapter, the credential store, and the UI for no clear gain.

## Consequences

- The first releasable APK exists much sooner — phases 0–7 partial is a credible 2–4 week scope.
- Users with extensive Proton contact data (postal addresses, notes, etc.) see only a subset of their data in v0.1.0. README is explicit about this.
- The post-MVP path is well-defined: each deferred field/feature is a discrete additive change behind the same auth/crypto/sync pipeline.
- No conflict resolution code exists in MVP. If a user edits a synced contact locally, those edits live on the local `RawContact` only (Android lets the user edit any contact); the next sync does **not** push them upstream. This matches MVP read-only intent.
- Logout must wipe cleanly. The Room mapping store and EncryptedSharedPreferences are both purged. Verified by an instrumented test.

## Validation

- After login + first sync against a test account, every contact's name and primary email appears in the system Contacts app.
- Running sync twice produces zero net `applyBatch` mutations the second time.
- Logout removes all `RawContacts` with our account type and clears EncryptedSharedPreferences.
- No decrypted vCard content appears in `adb logcat` during any sync (asserted via the redacting-logger Lint rule, ADR-0015).
