<!--
  SPDX-License-Identifier: GPL-3.0-only
  SPDX-FileCopyrightText: 2026 pcontacts contributors
-->

# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.1.0] - 2026-05-29

### Added

- Multi-key contact decrypt (ADR-0020). Sync now fetches
  `/core/v4/addresses`, decrypts each AddressKey's Token under the
  user primary, unlocks the address keys, and unions all unlockable
  user + address private keys into the decrypt path. Previously the
  first contact encrypted to an address key (the common case on real
  Proton mailboxes) aborted sync with
  `no encrypted data block for any of our N key(s)`.
- "Send via Proton Mail" per-email action chip (ADR-0021). One
  chip per email address on a Proton contact, rendered next to the
  Email row in the system Contacts app. Tap routes to Proton Mail
  Android via explicit-package `ACTION_SENDTO mailto:`, falling
  back to the Proton Mail web compose URL if the Android app isn't
  installed.
- Tap-to-expand dialogs for the unverified-contacts warning and the
  apps-with-contacts-access banner. The verification banner now
  opens a list of the affected contacts (resolved through
  `ContactsContract` so the user sees the merged display name);
  tapping a row opens that contact in the system Contacts app. The
  contacts-access banner collapses its 12-row inline list into a
  scrollable dialog.
- Dedicated 24dp brand drawable for the account-source icon
  Contacts apps render next to each linked-source row. Replaces the
  launcher-mipmap fallback that rendered as a generic silhouette
  in Fossify Contacts.
- README note that the synced list mirrors Proton's full address
  book, including auto-saved senders if the Proton-side setting is
  on. Documents why client-side filtering isn't an option (the
  metadata DTO carries no flag distinguishing manual vs auto-saved
  contacts).

### Fixed

- Sign-in-required notification fired ~10s after every sync on
  2FA accounts. SrpLoginOrchestrator now defers keyPassword
  derivation until after `/auth/2fa` succeeds, so the access token
  carries `scope=full` when `/users` + `/keys/salts` run.
  Previously those calls hit HTTP 403 (scope=self) and the failure
  was swallowed, leaving an unusable half-set-up session.
- WhatsApp / Telegram contact aggregation no longer loses the
  local name. Proton contacts with no FN/N now write a null
  `DISPLAY_NAME`, so Android's aggregator preserves the local
  RawContact's real name instead of overwriting it with a
  synthetic phone-number or email string.
- `StructuredName` Data row is omitted entirely when a Proton
  contact has no name pieces — avoids contributing an empty row
  the aggregator could still resolve to a degenerate default.
- Login orchestrator's diagnostic log line is no longer swallowed
  by the default `NoOpSink`. Debug builds wire `AndroidLogcatSink`
  so failures (KEY_PASSWORD_MISSING, KEY_UNLOCK_FAILED) are
  visible in logcat for on-device diagnosis.

### Changed

- `EmailSyncHash` bumped to a `v2:` format prefix so the writer's
  new chip rows land. First sync after upgrade rewrites every
  existing contact once to migrate the on-device hash; subsequent
  runs return to fast incremental skip. The one-shot rewrite
  takes ~10–12 min on a ~1100-contact mailbox.

## [1.0.3] - 2026-05-28

### Added

- In-app captcha (human verification) flow: when Proton issues a
  `Code 9001` challenge during login or sync, an isolated WebView
  loads `verify.proton.me`. After the captcha is solved, the
  verification token is attached to subsequent requests via
  `x-pm-human-verification-token{,-type}` headers until the session
  invalidates it. Replaces the previous Custom Tabs implementation,
  whose cookie-jar isolation prevented the token from reaching the
  app's HTTP stack. See ADR-0019.

### Fixed

- Stale captcha-token recovery: `Code 12087` ("CAPTCHA validation
  failed") now clears the stored verification token instead of
  looping indefinitely; the next sign-in attempt triggers a fresh
  captcha.
- Five exception-demotion sites that previously swallowed
  `HumanVerificationRequiredException` as generic auth or sync
  failures now propagate it so the captcha UI fires correctly across
  login, 2FA, contact-detail pulls, and outbox pushes.

### Removed

- `androidx.browser` (Custom Tabs) dependency — the in-app WebView
  replaces it.

## [1.0.2] - 2026-05-28

### Fixed

- Login password field uses `KeyboardType.Password` and disables
  autocorrect to prevent Android's input methods from altering
  passwords during entry.
- Crypto: added captured test vector covering `)@` special
  characters in passwords.
- F-Droid build metadata: updated build commit hash to include the
  APK signing-block fix.

## [1.0.1] - 2026-05-27

### Fixed

- Removed sudo block from F-Droid metadata that would fail on Debian
  Trixie build VMs (JDK 21 ships by default, JDK 17 is unavailable).

## [1.0.0] - 2026-05-27

### Added

- Settings screen surfaces which installed apps hold READ_CONTACTS,
  making it visible which apps can read synced Proton contacts.
- Italian and German translations for all new UI strings.

### Changed

- Promoted from pre-release to stable 1.0.0.

## [0.1.0] - 2026-05-25

### Added

- SRP login with TOTP two-factor authentication and modulus signature
  verification against a pinned Proton SRP signing key (ADR-0014).
- Full contact sync: FN, N (given/family/middle/prefix/suffix), EMAIL,
  TEL, ADR, ORG, TITLE, NOTE, IMPP, inline PHOTO, CATEGORIES, and
  Proton LabelIDs mapped to Android group membership.
- Client-side-only OpenPGP decrypt of CLEAR_TEXT, SIGNED, ENCRYPTED,
  and ENCRYPTED_AND_SIGNED contact cards via BouncyCastle (ADR-0007).
- Bidirectional sync with persistent outbox and push-before-pull
  ordering (ADR-0017, ADR-0018).
- Per-field three-way conflict detection with user-facing resolution
  dialog (use phone version / use Proton version).
- Soft-delete with 1-hour grace period and per-contact cancellation
  from the settings screen.
- Incremental sync keyed on server ModifyTime plus content hash to
  skip no-op writes.
- 401 token refresh under single-flight mutex; 429 Fibonacci backoff
  honouring Retry-After; 9001 human-verification surfaced to user.
- Periodic sync via SyncAdapter + WorkManager with configurable
  interval (15 min / 1 h / 6 h / 12 h / 24 h).
- Runtime contacts permission request with explanatory banner UI.
- Notification channels for sync status and action-required alerts.
- AppVersion rejection detection for Proton API drift.
- Secrets stored in EncryptedSharedPreferences under a Keystore AEAD
  key; keyPassword double-wrapped before persistence (ADR-0009).
- Reproducible-build CI gate via diffoscope (ADR-0003).
- OWASP Dependency-Check vulnerability scan (ADR-0015).
- Custom Lint rule blocking direct Log/println calls in core and
  feature modules (ADR-0015).
- Dependency license allowlist enforcement at build time (ADR-0015).
- Manifest invariant enforcement: allowBackup=false, debuggable=false
  on release, dataExtractionRules completeness (ADR-0009).
- F-Droid metadata in fastlane/metadata/android/en-US/.

### Security

- Decrypted contact data is never logged, persisted to disk, or
  transmitted off-device.
- No Google Play Services, no telemetry, no analytics, no remote
  configuration.
- OkHttp DNS resolver rejects hosts not matching *.proton.me.
- SPKI certificate pins for ISRG Root X1 + X2 enforced via OkHttp
  CertificatePinner.

[1.1.0]: https://github.com/andreabenetton/pcontacts/releases/tag/v1.1.0
[1.0.3]: https://github.com/andreabenetton/pcontacts/releases/tag/v1.0.3
[1.0.2]: https://github.com/andreabenetton/pcontacts/releases/tag/v1.0.2
[1.0.1]: https://github.com/andreabenetton/pcontacts/releases/tag/v1.0.1
[1.0.0]: https://github.com/andreabenetton/pcontacts/releases/tag/v1.0.0
[0.1.0]: https://github.com/andreabenetton/pcontacts/releases/tag/v0.1.0
