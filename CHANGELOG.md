<!--
  SPDX-License-Identifier: GPL-3.0-only
  SPDX-FileCopyrightText: 2026 pcontacts contributors
-->

# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.4.0] - 2026-08-05

### Changed

- **New launcher icon** — the three-node cluster mark with a
  violet-to-teal gradient replaces the old person-silhouette icon,
  across adaptive, monochrome, and legacy mipmap assets.
- Notifications now use a proper monochrome status-bar icon instead of
  misusing the launcher icon.

### Fixed

- **Ungrouped Proton contacts synced but stayed invisible on Contacts
  Providers with AOSP-default account settings (e.g. Mudita Kompakt).**
  The app never wrote the account-level `ContactsContract.Settings` row,
  and AOSP defaults `ungrouped_visible=0` for sync-adapter accounts, so
  contacts without a label were hidden from the device Contacts app even
  though they synced correctly. The row (`should_sync=1`,
  `ungrouped_visible=1`) is now written after login and re-ensured
  (idempotent upsert) at the start of every sync. Login also requests an
  immediate first sync instead of waiting for the scheduler.
- **The human-verification screen crashed on devices without a WebView
  provider** (possible on de-Googled distributions such as MuditaOS).
  It now detects the missing provider, logs, and cancels cleanly back to
  the login flow instead of crashing; all existing WebView security
  constraints are unchanged.

## [1.3.4] - 2026-07-28

### Fixed

- **Contacts failed to sync on release (F-Droid/signed) builds.**
  R8/minification tree-shook ez-vcard's reflectively-invoked
  `parameter`/`util` members (only `io.scribe` + `property` were kept),
  so every contact threw `NoSuchMethodException` during vCard parsing
  and was skipped — the account synced 0 contacts. Debug builds, being
  un-minified, were unaffected, which is why it only surfaced in the
  field. Added keep rules for the reflective packages (without dragging
  in ez-vcard's unused hCard/jsoup/freemarker path). Diagnosed live on
  a release build via 1.3.3's new production logging.

### Changed

- Sync failure logs now include the third-party throw-site frame
  (redacted, no contact content), so library-level bugs like the above
  are pinned directly instead of collapsing to the app boundary.

## [1.3.3] - 2026-07-28

### Fixed

- Sync is now resilient to individual bad contacts: a contact that
  fails to fetch, decrypt, or parse is skipped and counted instead of
  aborting the entire sync (one malformed contact on a large account
  previously failed every contact).
- Sync errors are classified honestly. Only genuine network/transport
  failures show "check your connection"; other failures (a bug or bad
  data) no longer blame the connection.

### Added

- The launcher reports how many contacts the last sync skipped
  ("N contacts couldn't be synced").
- Production sync logging now captures the real failure location — a
  redacted throwable fingerprint (class + in-project call path + cause
  chain, never any contact content) — and the pull path, which was
  previously wired to a no-op logger, now logs. Field sync failures are
  diagnosable from logcat.

## [1.3.2] - 2026-07-28

### Fixed

- "Last sync" is now recorded per sync run instead of being derived
  from stored contacts. Previously a sync that stored no rows — an
  empty account, contacts filtered out locally, or (most commonly) a
  sync that failed before writing — left "Last sync: never" with no
  indication a sync had run or failed. The launcher now shows a real
  last-sync time (even for a zero-contact account) and a localized
  "Last sync failed …" line explaining the failure (update required,
  sign in again, verification needed, or connection error).

## [1.3.1] - 2026-07-28

### Added

- Proton-style adaptive launcher icon: a Proton-purple → violet
  gradient tile with a single white contacts glyph, plus a monochrome
  layer for Android 13 themed icons.

### Changed

- Login failures now show clear, localized messages for every failure
  reason. Previously five reason codes (including the pinned-modulus
  MITM check and app-version rejection) leaked a raw internal token to
  the user; the modulus failures now surface a security warning and an
  out-of-window app version prompts an update. All login/2FA error
  strings are localized (en/it/de).

### Fixed

- The launcher home screen now updates its "Synced" count and "Last
  sync" line live when a sync completes while it is foregrounded (e.g.
  the initial sync right after sign-in), instead of only on the next
  resume.

## [1.3.0] - 2026-07-28

### Changed

- Build now compiles against Android 16 (compileSdk 36).
- Dependency updates: OkHttp 5.4.0, Kotest 6.2.2, Kover 0.9.8,
  KSP 2.3.9.

### Documentation

- Verified the `x-pm-appversion` acceptance window against the live
  Proton API (2026-07-28): `android-mail@2.0.0`–`3.0.12` are accepted
  for the direct `auth/info` SRP flow; `3.0.13` and newer (including
  the current 7.x line) return `401`. The pinned value stays at
  `android-mail@3.0.12`; it is a client identifier, not the latest
  app version. See `docs/API_RESEARCH.md` §2.

## [1.2.0] - 2026-06-22

### Added

- Second Settings transparency banner dedicated to preinstalled
  system apps that hold `READ_CONTACTS` (Google Play Services,
  Google Contacts, Gmail, OEM dialer / messaging / assistant). The
  existing user-app banner intentionally filters this category out;
  the new banner surfaces it explicitly and tap-expands into a
  dialog listing each package. Rendered in `errorContainer` color
  to distinguish from the neutral user-app banner. Detail copy
  recommends GrapheneOS / LineageOS as the remediation — the
  platform permission model is outside pcontacts' reach. Strings
  shipped in `en`, `it`, `de`.
- README "Known gaps" entry #1 covering the OS-level exposure: once
  contacts land in `ContactsContract`, every preinstalled system
  app with `READ_CONTACTS` can read them; pcontacts cannot mediate
  that.

### Fixed

- Settings screen drew behind the transparent status bar on
  Android 15+ (`targetSdk = 35` enforces edge-to-edge). The bare
  `Surface(modifier = Modifier.fillMaxSize())` had no inset
  awareness; both the "No Proton account. Sign in from the
  launcher." text and the signed-in `SettingsScreen` rendered
  flush to the top of the window. `Modifier.systemBarsPadding()`
  now applies. Reported by `@ianrosswilliams` during F-Droid
  device testing on Pixel 8 Pro / Android 16.

### Changed

- `LogoutHelper` constructor now takes an optional
  `ioDispatcher: CoroutineDispatcher = Dispatchers.IO`; both
  `withContext` calls route through it. Matches the manual-DI
  pattern used by ViewModels (no DI framework — see `CLAUDE.md`).
  Source-compatible with the previous single-arg call site.

### Internal

- detekt: `:app:detektDebug` / `:app:detektMain` /
  `:app:detektRelease` now pass alongside the existing `:detekt`
  root task. The remaining `RedundantSuspendModifier` cases are
  file-level `@Suppress`ed with a leading rationale comment — the
  rule requires Type Resolution to be accurate, and we don't run
  detekt with TR.
- CI: reproducible-build job now copies the unsigned APK that AGP
  actually emits (path drifted with the
  `base.archivesName = "pcontacts"` rename); OWASP scan scoped to
  the release runtime classpath with two false-positive CVEs
  suppressed.
- Live-API canary alignment: `LiveProtonWriteTest` skips on
  non-`Success` login so the canary mirrors the orchestrator's
  control flow exactly.

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

[1.4.0]: https://github.com/andreabenetton/pcontacts/releases/tag/v1.4.0
[1.3.4]: https://github.com/andreabenetton/pcontacts/releases/tag/v1.3.4
[1.3.3]: https://github.com/andreabenetton/pcontacts/releases/tag/v1.3.3
[1.3.2]: https://github.com/andreabenetton/pcontacts/releases/tag/v1.3.2
[1.3.1]: https://github.com/andreabenetton/pcontacts/releases/tag/v1.3.1
[1.3.0]: https://github.com/andreabenetton/pcontacts/releases/tag/v1.3.0
[1.2.0]: https://github.com/andreabenetton/pcontacts/releases/tag/v1.2.0
[1.1.0]: https://github.com/andreabenetton/pcontacts/releases/tag/v1.1.0
[1.0.3]: https://github.com/andreabenetton/pcontacts/releases/tag/v1.0.3
[1.0.2]: https://github.com/andreabenetton/pcontacts/releases/tag/v1.0.2
[1.0.1]: https://github.com/andreabenetton/pcontacts/releases/tag/v1.0.1
[1.0.0]: https://github.com/andreabenetton/pcontacts/releases/tag/v1.0.0
[0.1.0]: https://github.com/andreabenetton/pcontacts/releases/tag/v0.1.0
