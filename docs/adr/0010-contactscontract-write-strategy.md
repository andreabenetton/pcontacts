# ADR-0010: ContactsContract write strategy — delete-and-reinsert child rows

- **Status:** Accepted (amended 2026-11-17 — an update asserts the row is still clean; a batch is bounded in bytes)
- **Date:** 2026-05-22
- **Deciders:** project owner
- **Related:** ADR-0004, ADR-0008

## Context

For each Proton contact we sync, we write one `RawContacts` row and many `Data` rows (one per MIMETYPE-tuple — `StructuredName`, `Email`, `Phone`, …). On update, we have two choices for keeping the local data in sync:

1. **Diff-and-patch.** Read existing `Data` rows for the `RawContact`, compute a diff against the new vCard's properties, emit `INSERT`/`UPDATE`/`DELETE` ops per row.
2. **Delete-and-reinsert.** Delete all `Data` rows under the `RawContact`, then insert the new set. Keep the `RawContacts._ID` unchanged.

Diff-and-patch is "minimal mutation" but has subtle failure modes:

- Multi-value MIMETYPEs (multiple emails, phones) require a stable ordering scheme to know which row is "the same" across syncs. The vCard property has no stable ID we can rely on.
- Partial-update bugs (forgetting to clear `IS_PRIMARY` on an old row when a new primary appears, leaving stale rows when properties are removed) are easy to introduce.
- The diff path is `O(rows × fields)` and harder to test exhaustively.

Delete-and-reinsert is `O(rows)` and trivially idempotent. Concerns:

- It mutates more bytes per update. In practice, contact updates are rare (Proton contact `ModifyTime` changes only when the user edits in Proton) and `applyBatch` batches the ops in a single transaction; the SQLite cost is negligible.
- It does not destroy local-only state held on the **`Contacts`** row (starred, ringtone, custom photo) because those are aggregated state on the parent `Contacts._ID`, not the `RawContacts._ID`. We never delete or recreate the `RawContacts` itself — only its child `Data` rows.

Independently, all writes from a SyncAdapter must use the `?caller_is_syncadapter=true` query parameter — without it, `ContactsContract` writes tombstones for "deleted" rows that re-resurrect on the next merge cycle, causing duplicates.

## Decision

**Strategy:** delete-then-reinsert child `Data` rows under a stable `RawContacts._ID`.

**Algorithm (per contact update):**

```kotlin
val ops = ArrayList<ContentProviderOperation>()

ops += newDelete(Data.CONTENT_URI.buildUpon()
        .appendQueryParameter(CALLER_IS_SYNCADAPTER, "true").build())
        .withSelection("RAW_CONTACT_ID = ?", arrayOf(rawId.toString()))
        .build()

ops += /* insert StructuredName, Email[], Phone[], … with withValueBackReference where needed */

ops += newUpdate(RawContacts.CONTENT_URI.buildUpon()
        .appendQueryParameter(CALLER_IS_SYNCADAPTER, "true").build())
        .withSelection("_ID = ?", arrayOf(rawId.toString()))
        .withValue(RawContacts.SYNC1, newModifyTime.toString())  // ModifyTime
        .withValue(RawContacts.SYNC2, newContentHash)            // sha256(canonical vcard)
        .withValue(RawContacts.SYNC3, protonUid)                 // vCard UID from SIGNED card
        .build()

contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
```

**Algorithm (per contact create):**

- `newInsert(RawContacts.CONTENT_URI)` with `ACCOUNT_NAME`, `ACCOUNT_TYPE = "com.example.protoncontacts"`, `SOURCE_ID = protonContactId`, `SYNC1/2/3`.
- Subsequent `Data` inserts use `withValueBackReference(Data.RAW_CONTACT_ID, rawIdx)`.

**Algorithm (per contact delete):**

```kotlin
ops += newDelete(RawContacts.CONTENT_URI.buildUpon()
        .appendQueryParameter(CALLER_IS_SYNCADAPTER, "true").build())
        .withSelection("SOURCE_ID = ? AND ACCOUNT_TYPE = ?",
                       arrayOf(protonId, ACCOUNT_TYPE))
        .build()
```

`CALLER_IS_SYNCADAPTER=true` is **mandatory** on every write — set it once via a helper on `Uri.Builder` and use that helper exclusively.

**Batch size:** chunk `applyBatch` calls to ≤ 450 ops to stay below the binder transaction limit.

**Aggregation:** never set `AGGREGATION_MODE = SUSPENDED` or `DISABLED` unless we have a specific reason. Let Android merge our `RawContact` with same-name/same-email local contacts — that's the desired UX (one "Contact" with multiple raw sources).

## Alternatives considered

- **Diff-and-patch.** Rejected on testability and correctness grounds.
- **Delete the entire `RawContacts` row and reinsert.** Rejected — destroys aggregated `Contacts` row state the user owns (starred, custom photo, custom ringtone).
- **Don't use `CALLER_IS_SYNCADAPTER`.** Rejected — guaranteed duplicate-row bug.

## Consequences

- Update path is single-test-case: "given old data row set X, new vCard Y, after applyBatch the data rows match Y exactly".
- Aggregator-related test surface is small. We test "Android merged our RawContact with a local one and the result is correct" once and trust the platform.
- Binder transaction limit forces chunked batches. We never `applyBatch` an unbounded list.
- All writes through one helper that enforces `CALLER_IS_SYNCADAPTER`; a detekt rule forbids direct `ContentResolver.applyBatch` calls outside `:core:contacts-writer`.

## Validation

- Idempotent-sync instrumented test: run sync twice in a row, second run produces zero `applyBatch` mutations.
- Update-shape instrumented test: write rows from vCard A, then from vCard B, query and assert the row set matches B exactly (no leftovers).
- Aggregator test: insert one local contact with the same email, run sync, assert system `Contacts` row count is 1 (aggregated) and both `RawContacts` are present underneath.
- Delete-tombstone test: delete a synced contact, run another sync, assert it doesn't reappear.

## Implementation status

Writer module shipped end-to-end:

- `SyncAdapterUri.decorate(uri, accountName, accountType)` is the single helper that decorates every write URI with `caller_is_syncadapter=true` + the account params. No other code in the repo builds a ContactsContract write URI directly.
- `RawContactDiffer` (pure-JVM) classifies Create / Update / Delete intents from `(target, existing, serverSourceIds)`.
- `ContactsContractOps.build(account, intent, baseIdx)` emits the per-intent ContentProviderOperation list. Update path is delete-then-reinsert child Data rows under a stable `RawContacts._ID` per this ADR's Decision.
- `BatchPlanner.plan(...)` chunks ops to ≤450 per batch AND re-anchors back-references when a Create intent straddles a chunk boundary (so back-ref indices stay correct after chunking).
- `BatchApplier` is the only legitimate caller of `provider.applyBatch` in the repo. Adds `deleteAllForAccount(account)` for the logout path.
- `RawContactReader` queries existing RawContacts by `ACCOUNT_TYPE` + `ACCOUNT_NAME`; cursor parser split out for MatrixCursor-backed tests.
- `LocalGroupsWriter.reconcile(account, labels)` runs the ContactsContract.Groups lifecycle (create new, delete vanished, return Map<protonLabelId, localGroupsId>).
- `PhotoDownscaler` fits inline-Photo bytes into ContactsContract's ~96KB soft cap (quality slide then dimension halving; returns null and the writer drops the photo if even 64×64 doesn't fit).

Data row coverage in `ContactRow` (and emitted by `ContactsContractOps`):

- StructuredName: DISPLAY_NAME + per-piece columns (GIVEN, FAMILY, MIDDLE, PREFIX, SUFFIX).
- Email: one row per address; position 0 marked IS_PRIMARY + IS_SUPER_PRIMARY.
- Phone: one row per number with PhoneType enum → Phone.TYPE_* via `PhoneTypeMapper`.
- StructuredPostal: one row per address with all 7 RFC 6350 components; PostalAddressType enum → StructuredPostal.TYPE_*.
- Organization: company + department + title.
- Note: one row per note.
- Im: handle + protocol + customProtocol via `ImProtocolMapper` (named tiers → `Im.PROTOCOL_*`, unknowns fall back to PROTOCOL_CUSTOM + the original scheme as label).
- Photo: inline BLOB, downscaled if needed.
- GroupMembership: one row per resolved group row id from `LocalGroupsWriter`.

Init guard widened from "≥ 1 email" to "≥ 1 of {email, phone, address, im}" so phone-only / address-only / IM-only Proton contacts surface.

Deferred:

- The four Validation instrumented tests (idempotency, update-shape, aggregator, delete-tombstone) need an emulator pipeline. Robolectric `ContactsContractOpsTest` / `BatchPlannerTest` / `RawContactDifferTest` / `LocalGroupsWriterTest` cover structural assertions; the provider-hitting paths land with the device-validation chain.

## Amendment (2026-11-17): an update asserts the row is still clean, in the same batch

The pull checks which rows the user edited (DIRTY) before it writes, and
leaves those for the next run. A user edit landing between that check
and the batch was overwritten: the delete-and-reinsert replaced it, and
the next run found nothing to send.

Every update now starts with an assert operation on its RawContact,
`_ID = ? AND DIRTY = 0` with an expected count of 1, in the same batch as
its delete and re-inserts (the batch planner never splits an intent).
A row edited in the meantime fails the batch; nothing of it is applied,
and the pull leaves that row to the next run. `[A]` ContactsProvider
applies a batch in one transaction when no operation allows a yield, and
none of ours does.

Validation: `ContactsContractInstrumentedTest.an_update_does_not_overwrite_a_row_edited_since_it_was_checked`
passed on Android 11 (Samsung SM-A405FN), 2026-11-17; the existing update
tests show a clean row is still updated (review of 2026-11-17, F5).

## Amendment (2026-11-17, second): a batch is bounded in bytes as well as in operations

The 450-operation cap was the only bound on an `applyBatch`, but one
batch travels in a single Binder transaction, whose 1 MiB buffer the
whole process shares. Twenty contacts with a photo just under the 96 KiB
inline limit are about 80 operations and 1.7 MB: Android refused the
batch with `TransactionTooLargeException`, and the next run built the
same batch again, so the pull never got past it.

The batch planner now also closes a batch before it would exceed
384 KiB `[A]` (headroom under the shared buffer), measuring each intent
as it is parcelled. An intent is never split, so a contact's operations
and back-references stay together; one contact over the budget on its
own travels alone.

Validation: `BatchPlannerTest.contacts_with_large_photos_are_split_by_bytes_not_only_by_op_count`;
`ContactsContractInstrumentedTest.twenty_contacts_with_large_photos_are_written_in_batches_binder_can_carry`
on Android 11 (Samsung SM-A405FN), 2026-11-17: without the budget the batch
was 1,738,548 bytes and failed with `TransactionTooLargeException`; with
it, all twenty were written (review of 2026-11-17, F6).
