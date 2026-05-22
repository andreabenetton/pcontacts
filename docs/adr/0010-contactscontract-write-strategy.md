# ADR-0010: ContactsContract write strategy — delete-and-reinsert child rows

- **Status:** Accepted
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
