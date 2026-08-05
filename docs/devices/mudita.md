<!--
  SPDX-License-Identifier: GPL-3.0-only
  SPDX-FileCopyrightText: 2026 pcontacts contributors
-->

# Mudita Kompakt (MuditaOS K)

De-Googled Android distribution on e-ink hardware. Standard Contacts
Provider, no Google Play Services, and possibly **no enabled WebView
provider**. Mudita ships a DAVx5 fork
([mudita/davx5-ose-kompakt](https://github.com/mudita/davx5-ose-kompakt))
whose contacts code is unmodified upstream DAVx5 — a useful reference
implementation for what works on this device.

## Issue 1 — synced contacts invisible without the Settings row

`[V]` AOSP's ContactsProvider defaults `ungrouped_visible=0` for every
sync-adapter-owned account (`ContactsDatabaseHelper`: Android 12+ column
default on the accounts table; ≤11 a missing settings row evaluates as
invisible). A contact with no group membership is only visible if its
account's `ungrouped_visible=1`. Proton contacts without a label
therefore synced correctly but never appeared in Mudita Contacts (or in
caller ID).

**Fix (cross-device, shipped in `0e26846`):**
`ContactsAccountSettings.ensureVisibleAndSyncable` writes the
account-level `ContactsContract.Settings` row (`should_sync=1`,
`ungrouped_visible=1`) after login and again at the start of every sync.

- `[V]` `ContactsProvider2.insertSettings` treats the insert as an
  upsert keyed on `account_name`/`account_type` — idempotent, safe to
  repeat, touches no contact rows.
- `[V]` DAVx5 (including Mudita's fork) performs the identical write via
  `Settings.CONTENT_URI` with `caller_is_syncadapter=true` on every
  address-book create/update — which is why DAVx5 contacts are visible
  on the same device.
- Non-fatal by design: a broken/non-standard OEM provider logs a
  redacted error and sync continues; the next sync retries (self-heals
  after OS updates, provider database resets, account restoration).
- No device detection: correct account initialization on every Android
  device, not a Mudita branch.

## Issue 2 — human verification with no WebView provider

`HumanVerificationActivity` used to construct `WebView(this)`
unconditionally; on a device without an enabled WebView provider the
constructor throws (`AndroidRuntimeException`) and the app crashed.

**Fix (shipped in `f959dce`):** `[V]`
`WebView.getCurrentWebViewPackage()` (API 26 == minSdk) returns null
when no provider is enabled; the activity now checks it, also catches a
constructor-time `RuntimeException` (provider disabled between check and
construction), and finishes with `RESULT_CANCELED` instead of crashing.
All WebView security constraints (proton.me-only navigation, no
file/content access, DOM storage off, scoped JS bridge) are unchanged.
The app never installs, enables, or selects a WebView provider.

Known limitation: the cancellation is silent (LoginActivity resets to
the login form; the sync path re-posts the verification notification).
Surfacing the existing `verification_fallback_*` dialog on this path is
a deferred follow-up — it needs result plumbing in LoginActivity's
Compose tree and an ActivityResultLauncher conversion of
`HumanVerificationLauncher` for the MainActivity path.

## Hardware validation performed (non-Kompakt)

2026-08-05, Samsung Galaxy A40 (SM-A405FN), Android 11 / One UI — the
regression side of the device matrix, and an OEM provider on the ≤11
separate-settings-table code path. Validated via `adb shell content`
with a probe raw contact under `io.pcontacts.account` (cleaned up
afterwards):

- no settings row existed for the account; the ungrouped probe contact
  aggregated with `in_visible_group=0` — the invisibility root cause
  reproduces on real OEM hardware, not just in AOSP source;
- the exact write `ContactsAccountSettings` performs created the row
  (`ungrouped_visible=1`, `should_sync=1`) and the contact flipped to
  `in_visible_group=1`;
- a second identical insert upserted (still one row, no error) —
  idempotency holds on this OEM provider;
- debug APK installs and launches cleanly; `HumanVerificationActivity`
  is confirmed non-exported (not launchable from outside the app).

Same device, app-driven end-to-end (test account login with 2FA):
immediately after login the app itself created the settings row
(`ungrouped_visible=1`, `should_sync=1`), the login-requested first sync
fired within seconds and pulled both server contacts, the follow-up
framework auto-sync re-ensured the row idempotently (`unchanged=2`, no
errors), and both contacts aggregated with `in_visible_group=1`.

Not covered: the no-WebView path (device has a provider) — Kompakt
hardware still needed for that and for Mudita's own Contacts app.

## On-device validation checklist (needs Kompakt hardware)

Automated coverage (Robolectric) proves URI shape, values, ordering, and
the cancellation paths — not real MuditaOS behavior. On hardware:

1. Log in, then verify the settings row:

   ```bash
   adb shell content query \
     --uri content://com.android.contacts/settings \
     --where "account_type='io.pcontacts.account'"
   ```

   Expected: `should_sync=1`, `ungrouped_visible=1`.
2. An **unlabeled** Proton contact appears in Mudita Contacts.
3. A labeled Proton contact still appears.
4. Caller ID resolves an incoming call from an unlabeled contact.
5. Sync still works after reboot, without reopening pcontacts.
6. A Proton human-verification challenge (Code 9001) does not crash;
   the screen cancels cleanly if no WebView is present.
7. Regression pass on a normal Android device: contacts visible,
   editable, sync behavior unchanged.
