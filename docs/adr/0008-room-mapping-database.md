# ADR-0008: Local mapping store — Room for ProtonID ↔ RawContactID

- **Status:** Accepted
- **Date:** 2026-05-22
- **Deciders:** project owner
- **Related:** ADR-0006, ADR-0007, ADR-0009

## Context

The sync engine must answer, on every run:

1. "Which Proton contacts are new since last sync?" — diff `ContactMetadata.ID` against what we've seen.
2. "Which Proton contacts have changed?" — diff `ModifyTime` per ID.
3. "Which Proton contacts were deleted server-side?" — IDs we know about that no longer appear in the listing.
4. "Did the decrypted content actually change?" — `ModifyTime` can bump for reasons that don't change visible data; we re-write `RawContacts` only when the content hash differs.
5. "Which `RawContacts._ID` corresponds to a given Proton contact ID?" — for batched updates without re-querying by `SOURCE_ID` each time.

We cannot keep this state in `ContactsContract` alone because:

- `SOURCE_ID` works for ProtonID, but reading `SYNC1/2/3` columns at scale requires a query per row.
- Storing a content hash and a `ModifyTime` in the contacts provider is fragile (the user could clear contact data and break us).
- We need a place to track sync metadata (last full sync timestamp, last incremental sync timestamp, last known server total) that is not part of any individual contact row.

A separate, app-owned local DB resolves these. The store must not hold decrypted content (ADR-0007) — only IDs, timestamps, and hashes.

## Decision

Use **Room** (`androidx.room:room-runtime`, `room-ktx`, `room-compiler`) as the local DB, in module `:core:storage`.

Schema (initial):

```kotlin
@Entity(
    tableName = "contact_map",
    indices = [Index("android_raw_contact_id"), Index("proton_uid")]
)
data class ContactMap(
    @PrimaryKey val proton_contact_id: String,
    val proton_uid: String?,                 // vCard UID from SIGNED card
    val android_raw_contact_id: Long,        // RawContacts._ID after insert
    val modify_time: Long,                   // ContactMetadata.ModifyTime, seconds since epoch
    val content_hash: String,                // SHA-256 hex of canonical decrypted vCard
    val is_verified: Boolean,                // false if any Card failed signature verification
    val deleted: Boolean,                    // tombstone for two-step deletion
    val sync_status: Int,                    // 0=clean, 1=pending_pull, 2=pending_push, 3=conflict, 4=error
    val last_error: String?,                 // redacted (class name + opcode only)
    val last_synced_at: Long                 // wall clock
)

@Entity(tableName = "group_map")
data class GroupMap(
    @PrimaryKey val proton_label_id: String,
    val android_group_id: Long,
    val name: String,
    val modify_time: Long
)

@Entity(tableName = "sync_state")
data class SyncState(
    @PrimaryKey val account_name: String,
    val last_full_sync_at: Long,
    val last_incremental_sync_at: Long,
    val last_known_total: Int
)
```

**Encryption-at-rest:** Room DB file is **not** encrypted in MVP. It contains only IDs, hashes, and timestamps — no contact content, no tokens. Re-evaluate (SQLCipher) if a future schema holds anything sensitive.

**Migrations:** every schema change ships an explicit `Migration` object. `MigrationTestHelper`-driven tests load v(N) → migrate → assert v(N+1) shape.

**Backup:** `android:allowBackup="false"` (ADR-0009) means this DB is never included in auto-backup either.

## Alternatives considered

- **No local DB; query `ContactsContract` for everything.** Rejected — slow at scale, fragile to user clearing contacts, no place to record sync state.
- **DataStore (Proto or Preferences) for the mapping.** Rejected — wrong shape for thousands of rows with multiple lookups.
- **Use `RawContacts.SYNC1/2/3/4` as the canonical store.** Considered. We will use these columns redundantly so the system can reconstruct after a Room wipe, but Room is the authoritative source.
- **SQLCipher from day one.** Rejected — adds binary blob (anti-feature pressure on F-Droid), build complexity, and key management for data that isn't sensitive (no decrypted content, no tokens).

## Consequences

- `:core:storage` owns the DAO surface; `:core:sync` calls it. Other modules never touch the DB directly.
- A clean reinstall produces an empty mapping store; first sync after reinstall is a full sync. That's acceptable.
- If the user clears app data, Room is wiped but the `RawContacts` may remain. Our next sync queries by `ACCOUNT_TYPE` and `SOURCE_ID` to rebuild the mapping before applying any changes. This recovery path is explicitly tested.
- Migration tests are mandatory for every schema PR.

## Validation

- Schema v1 round-trip test: insert, read, update, delete.
- Migration test scaffold present from day one (even if there's only v1).
- After a clean reinstall, the first sync produces correct counts (queried directly via `ContentResolver` and compared to server).
- "Clear app data" scenario: simulate by wiping Room only, then run sync, assert reconciliation with existing `RawContacts`.
