# ADR-0004: System integration — AccountAuthenticator + SyncAdapter, with WorkManager fallback

- **Status:** Accepted
- **Date:** 2026-05-22
- **Deciders:** project owner

## Context

For Proton contacts to appear in the system Contacts app, the app must own rows in `ContactsContract.RawContacts` whose `ACCOUNT_TYPE` matches a registered account type. Android's two relevant first-class mechanisms are:

1. **`AbstractAccountAuthenticator`** — registers our account type so the user can add a "Proton Contacts" account in Settings → Accounts and see it as a sync source in the Contacts app's account filter.
2. **`AbstractThreadedSyncAdapter`** — registers a sync handler against `ContactsContract.AUTHORITY`. Drives the system Contacts app's pull-to-refresh, "sync now" overflow item, and `Settings.SHOULD_SYNC` toggles.

Both have been stable AOSP APIs since API 5 and are still the only way to participate in `ContactsContract` as a first-class account source — there is no modern WorkManager-only equivalent that gets the system Contacts UI affordances.

WorkManager is the modern way to do periodic background work, but:

- It does not surface sync state in the system Contacts UI.
- It is more reliable on aggressive vendor power profiles (Xiaomi, Huawei, OnePlus battery optimizers frequently suppress SyncAdapter scheduling).

## Decision

Implement both:

- **`ProtonAuthenticatorService extends Service`** hosting an `AbstractAccountAuthenticator` for account type `com.example.protoncontacts` (final id TBD). Manifest declares the service with intent filter `android.accounts.AccountAuthenticator` and a metadata reference to `res/xml/authenticator.xml`.
- **`ProtonSyncService extends Service`** hosting an `AbstractThreadedSyncAdapter` against `ContactsContract.AUTHORITY` ("com.android.contacts"). Manifest declares the service with intent filter `android.content.SyncAdapter` and `res/xml/syncadapter.xml`. `userVisible="true"`, `supportsUploading="false"` for MVP, `allowParallelSyncs="false"`, `isAlwaysSyncable="true"`.
- **WorkManager `PeriodicWorkRequest`** scheduled at the same cadence (default 12 h) that triggers `ContentResolver.requestSync(account, AUTHORITY, …)`. The SyncAdapter remains the single place that actually performs sync; WorkManager only re-arms the trigger when the OS suppresses SyncAdapter scheduling.

For phase 9 (bidirectional sync) we set `supportsUploading="true"` and register a `ContentObserver` on `ContactsContract.RawContacts.CONTENT_URI` filtered to our account type.

## Alternatives considered

- **WorkManager only, write to `ContactsContract` directly.** Rejected — works, but the account doesn't appear in Settings → Accounts as a true account source, the user cannot toggle sync from system UI, and the experience is jankier (no pull-to-refresh).
- **Foreground service only.** Rejected — would draw a persistent notification, drains battery, and breaks Doze.
- **JobScheduler directly.** Rejected — WorkManager is the recommended abstraction and supports the same constraints with less boilerplate.

## Consequences

- We own three manifest service declarations and two XML descriptors.
- The user can disable sync at any time from system settings; we honor that and stop initiating syncs (`ContentResolver.getSyncAutomatically()`).
- Adding a Proton account from Settings → Accounts → Add Account works the same way Google/Microsoft/Nextcloud accounts do.
- `CALLER_IS_SYNCADAPTER=true` query parameter is mandatory on all `RawContacts`/`Data` deletes — without it, Android writes tombstones and the next sync recreates duplicates. (See ADR-0010.)
- Vendor battery optimizers may still suppress sync. README documents the workaround (whitelist the app). The WorkManager belt-and-suspenders catches most cases.
- We do not register a `ContentProvider` — we are a consumer of `ContactsContract`, not a provider.

## Validation

- On a clean device, Settings → Accounts shows "Proton Contacts" after first login.
- System Contacts app shows our contacts under the account filter.
- Pull-to-refresh in system Contacts triggers our SyncAdapter (logged in debug build).
- Removing the account via Settings deletes all our `RawContacts` (Android does this automatically when the account type is unregistered).

## Implementation status

All three surfaces are shipped:

- `ProtonAccountAuthenticator` + `ProtonAuthenticatorService` in `app/src/main/kotlin/io/pcontacts/app/account/`. `authenticator.xml` registers the `io.pcontacts.account` type. `addAccount` returns an Intent that opens `LoginActivity`.
- `ProtonSyncAdapter` + `ProtonSyncService` in `app/src/main/kotlin/io/pcontacts/app/sync/`. `syncadapter.xml` binds to `ContactsContract.AUTHORITY`. `onPerformSync` builds a `ContactDetailSyncEngine` via `SyncBootstrap.createContactDetailSyncEngine` and bridges its suspend `sync()` via `runBlocking`. Maps `DecryptUnavailableException` and `HumanVerificationRequiredException` to `syncResult.stats.numAuthExceptions` so the framework stops retrying.
- `PeriodicSyncWorker` + `SyncScheduler` in `app/src/main/kotlin/io/pcontacts/app/sync/`. Schedules a 12-hour periodic job (NetworkType.CONNECTED + battery-not-low + KEEP policy). `PcontactsApplication.onCreate` enqueues it idempotently.
- In-app `SettingsActivity` (Compose surface from `:feature:settings`) wires the user-facing "Sync now" (`ContentResolver.requestSync` expedited + manual) and "Sign out" (`LogoutHelper` → `LogoutOrchestrator`) actions.
- `LogoutHelper` runs the full server-revoke + ContactsContract wipe + Room wipe + SecretStore wipe + AccountManager removal chain.
