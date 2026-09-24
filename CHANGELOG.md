<!--
  SPDX-License-Identifier: GPL-3.0-only
  SPDX-FileCopyrightText: 2026 pcontacts contributors
-->

# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed

- **The sync card names each stage of a run with its own count**:
  "Connecting to Proton", "Sending changes to Proton: 2 of 7",
  "Checking contacts",
  "Downloading contacts: 3 of 7", "Saving to your contacts". The
  download count is the contacts that changed on Proton, not every
  contact on the account — a sync with 7 changes no longer counts to
  902. A stage with nothing to do is not shown.
- The sync card shows the contact count and "Last sync" on separate
  lines.

### Fixed

- **Contacts deleted on a Samsung came back.** Samsung Contacts moves a
  deleted contact into its Recycle bin, hidden from sync apps, so the
  deletion never reached Proton and the next sync recreated the contact.
  On a phone with such a bin, a synced contact that disappears is now
  put to the user — delete it from Proton, or put it back — instead of
  being recreated (ADR-0022 amendment). Found on a Samsung A40
  (Android 11).
- **"Overdue" next to a recent "Last sync"**. Overdue was measured from
  the last sync that settled everything, so a contact left in conflict
  kept the card overdue while syncs kept running. It is now measured
  from the last completed sync — the time the card shows — and a
  contact waiting for a decision gets its own headline: "A contact
  needs your decision".

### Added

- **A 3-hour sync interval** on the slider, between 1 and 6 hours.

## [2.0.0] - 2026-09-22

### Added

- **Contribute section** at the bottom of the main screen — a word on the
  repository and "buy me a coffee" with a bitcoin address that is copied
  with a tap — and a GitHub mark at the top right of the app bar that
  opens the source repository in the browser.
- **Dependency audit in the app** (ADR-0024). A "Dependencies" chip
  next to the version opens a screen listing every shipped artifact
  with version, license and known advisories linked to osv.dev. The
  list is a snapshot generated at build time from one osv.dev query and
  committed to the repository; CI fails when it no longer matches the
  resolved classpath or when osv.dev reports an open advisory it does
  not list. By default the app looks nothing up while it runs, so an
  advisory published after the release shows up with the next one. A
  snapshot shipped with an open advisory is announced once per version
  by a notification. OWASP Dependency-Check runs in CI as well, against
  the National Vulnerability Database, on every push and weekly, and
  fails on an unsuppressed score of 7.0 or higher; the notes of its
  reviewed suppressions are what the screen gives as the reason an
  advisory does not apply, and a scanner mismatch leaves the artifact
  green rather than amber.
- **Optional runtime advisory check** (ADR-0025), off by default. A
  switch in Privacy says in plain words what leaves the device: the
  list of this version's artifacts, sent once a day to osv.dev, which
  then sees the device's address and can infer the app from the
  artifacts asked — a privacy-versus-security trade-off left to the
  user. Only while it is on does the chip carry a verdict, from osv.dev
  alone: "Dependencies OK", assessed (amber) or vulnerability (red).
  New advisories appear on the Dependencies screen with their osv.dev
  link and are announced once; a "Check now" runs the check on demand.
  An advisory can be muted, which counts as assessed until the artifact
  changes version. Nothing else in the app depends on the answer.
- **Import details from linked contacts** (ADR-0023). The app lists
  every contact that exists only in other providers (WhatsApp,
  Telegram, device-local, …) or whose Proton copy lacks details, with
  the providers' icons. Each contact can be reviewed field by field and
  the chosen details copied into the Proton copy — or a Proton copy
  created when there is none. Search, filters and bulk import are
  available; imported rows show "Syncing…" then "Added to Proton".
  The flow is one-way: other apps' contacts are never modified.
- **Contact-access transparency.** The Privacy section lists the
  user-installed and the OS-installed apps that hold the Contacts
  permission, with their icons, and opens the system page where the
  permission can be revoked (with directions when Android only
  exposes a page nearby).
- **"De-Googled Android ROMs" screen** explaining the term and listing
  GrapheneOS, CalyxOS, iodéOS, /e/OS, LineageOS for microG, LineageOS,
  ShiftOS-L and Replicant with their approach to Google services and a
  link to each official site (opened in the browser). Reached from the
  OS-installed-apps notice and from the sign-in screen while the
  Contacts permission has not been granted yet.
- **Default account for new contacts** button opening the system
  setting (disabled with an explanation where Android has no such
  page).
- The failed-changes dialog opens a contact in the system Contacts app
  on tap. The installed version is shown under the app name.

### Changed

- **One root screen** replaces the launcher and Settings: an app bar
  with the launcher icon and name, then Sync, Contacts, Privacy and
  Account sections. Before sign-in the same shell shows only the
  Account section with a green Sign in; Sign out is red and last.
- **Sync status card** with a headline that reflects real state (up to
  date, overdue, changes waiting, failures, not synced yet), live
  progress "Syncing N of M…", the contact count and a relative
  last-sync time (tap for the absolute time), and tappable rows for
  unverified contacts, pending and failed changes, scheduled deletions
  and conflicts. Sync now sits in the card.
- Sync interval is a stepped slider (Off / 1 / 6 / 12 / 24 h). "Off"
  turns Android's Contacts sync off for the Proton account and any
  interval turns it back on; the slider reflects a switch flipped in
  Android Settings, and when the phone-wide "Auto-sync data" switch is
  off the sync card says that only "Sync now" works and opens Android's
  sync settings.
- One sync-state vocabulary (spinner / check / warning) on every
  surface that reports a sync.
- Permissions are requested after sign-in, so a first launch shows the
  sign-in screen undisturbed; the first sync starts as soon as Contacts
  access is granted, whether from the prompt or from system Settings.
  Signing out resets the prompt so the next sign-in asks again.
- Login and two-factor screens use the same shell and section style as
  the app, with keyboard Next / Done actions; the two-factor prompt
  names the Proton code.
- Screen-reader labels for import checkboxes and the interval slider.
- All new text is available in English, German, Spanish, French,
  Italian, Russian and Simplified Chinese.

### Fixed

- **Editing a contact Proton created by itself now syncs.** A contact
  Proton auto-saved from a sent mail is stored as a single clear-text
  card; a rename or any edit of it on the phone was refused by Proton
  ("HTTP 400") and parked as a failed change. The update now rebuilds
  such a contact into the signed and encrypted cards Proton's own client
  writes, as vCard 4.0. A refused change also records Proton's error
  code next to the HTTP status.

- **Three-way merge used an empty base.** A field deleted on Proton was
  resurrected from the phone and a field deleted on the phone was
  resurrected from Proton, and unchanged fields could read as
  conflicts. The merge now runs against the real last-known server
  state, kept per contact sealed under the device Keystore; a missing
  base is shown as a conflict, never merged as empty.
- **Conflicts can now be resolved.** "Use phone version" pushes the
  local contact as-is; "use Proton version" makes the next sync
  rewrite it. Before, both choices re-entered the same conflict.
- **Outbox no longer queues duplicates.** Edits to the same contact
  coalesce into one pending change, so two edits between syncs are one
  request and a new contact can never be created twice on Proton.
  Deleting a contact that never reached Proton drops the pending
  create instead of creating and then deleting it.
- **Android's sync switch is honoured.** The periodic background sync
  and the refresh after a permission grant no longer bypass "Sync off"
  in Android Settings; only "Sync now", sign-in and a linked import do.
- **Verification during two-factor sign-in works.** A captcha demanded
  while entering the code opens the verification page and returns to
  the code screen for a fresh code, instead of a dead-end error. One
  demanded after the code was accepted resumes sign-in on the same
  session — it never asks for another code.
- **An edit on the phone no longer erases what Proton knows and the
  phone cannot show.** An update now patches the contact's current
  cards on the server: birthdays, websites, nicknames, groups, e-mail
  parameters, keys and any other property outside the phone's model
  survive an unrelated local edit (ADR-0017, Choice 2C).
- **A newer Proton photo survives an unrelated local edit**, and a photo
  changed on both sides is a conflict like any other field.
- **A Labels outage no longer wipes group memberships.** While the
  label list is unavailable the memberships already on the phone are
  kept and the contacts are re-checked once it is back.
- **A contact reduced to a name on Proton is updated on the phone**,
  instead of keeping its stale phone number or e-mail forever.
- **A detected conflict can no longer be wiped out by the pull that
  follows the push.** A row the phone still owns a change to — a
  conflict awaiting the user, or a change still queued or quarantined —
  is neither overwritten by the server version nor deleted when the
  server no longer has the contact. A contact deleted on Proton after it
  was changed on the phone becomes a "deleted on Proton" conflict: the
  phone version creates it on Proton again, the Proton version deletes
  it here too. Only the user's decision returns such a row to normal.
- **A create is counted as done only with a confirmed server identity.**
  An accepted batch without an item, or an item without a contact, is
  settled by the identity lookup or stays queued and is retried; before,
  it could be dropped from the outbox as "created" with no server id,
  leaving the contact permanently unsynced. A delete whose acknowledgement
  is missing is likewise retried instead of taken as done.
- **Leaving the sign-in flow by any route wipes the half session.**
  System back on the code screen now aborts the login like the explicit
  cancel does, so no partial tokens stay in the secret store.
- **Cancelling a sync no longer marks the pending change as failed.**
- **Cancelling a deletion brings the contact back** on the phone (or
  re-fetches it) instead of only forgetting the queued delete.
- **Import details** re-reads the contact when you confirm and asks you
  to look again if it changed meanwhile; an imported row says "Added
  to Proton" only once Proton accepted it, otherwise it says waiting or
  failed.
- **A create whose answer was lost** is recognised on Proton by its
  identity instead of being sent again or reported as failed.
- **"Up to date" means it**: a run that left contacts or changes behind
  shows attention, and the card shows when the last run happened. A
  background sync that leaves contacts or changes behind also posts a
  notification, once per change of the count and taken down by the
  next clean run; a sync started from the app stays quiet because the
  card is on screen.
- Two postal addresses that share a street and city are no longer
  treated as the same address.
- Leaving the sign-in flow half-way now wipes the partial session at
  once; a rotated token pair is stored in one write.
- The sync card no longer says "Last sync: In 0 minutes" right after a
  sync completes.
- The import list keeps its scroll position across the rescan that
  follows an import.

### Security

- **Sign-out is durable.** Secrets are wiped with a synchronous commit
  and the Keystore key is deleted and verified; if that fails the app
  stays signed in and says so instead of leaving secrets on disk. Sign
  out also forgets the account's sync bookkeeping.
- **Secrets are sealed by the Android Keystore directly.** The
  deprecated `androidx.security:security-crypto` (alpha) and Tink are
  gone. After updating from 1.x, sign in again once: the old session
  material cannot be carried over, so on first start the app signs the
  account out by itself and the sign-in screen explains why. This is a
  full resync: the synced contacts are removed from the phone and every
  one of them is downloaded again on the first sync after sign-in;
  local edits not yet pushed at that moment are lost, and stars,
  ringtones or links set on those rows on the phone are not kept. A background
  sync that runs before the app is opened posts the same explanation
  as a notification. Without Contacts access the sign-out waits for
  the permission instead of crashing at launch, and runs as soon as
  it is granted. The "sign in again" notice is posted the moment the
  update is installed, as a heads-up alert on its own high-importance
  channel, so nobody has to discover it at the next sync; F-Droid's
  "What's new" for this version leads with the same line.
- **The sync-adapter and authenticator services are no longer
  exported**, and the sync binder is handed out only to the system's
  sync-adapter bind.
- **The verification WebView is locked to `https://verify.proton.me`**
  for the document and navigation, to `proton.me` hosts for
  subresources, and its bridge accepts a single, bounded ASCII token
  only from that page. The verification URL is built with proper
  encoding.
- **"Send via Proton Mail" validates its caller's URI**: it acts only on
  a Contacts row of its own type under the pcontacts account.
- **CI actions are pinned to commit SHAs**, releases run in a protected
  environment and re-run the verification gates before signing, and
  the emulator matrix now includes API 35. A release tag must name the
  app's version and must point at a commit the build workflow passed
  in full.
- **Address keys are trusted only with a valid Token signature**
  (ADR-0020): a key whose Token is unsigned or signed by a foreign key
  is skipped and never used to verify contact cards.
- **Proton's own error codes inside successful HTTP answers are
  refusals**, never read as data; per-item codes of batch calls too.
- WorkManager updated to 2.11.2.

## [1.7.2] - 2026-09-19

### Security

- Updated Apache FreeMarker to 2.3.35, fixing a path-traversal advisory
  (CVE-2026-84939) in the version pulled in transitively by the vCard
  library. The affected template feature is not used by pcontacts, so
  the flaw was not reachable; this is a hygiene update.

### Changed

- Dependency and toolchain maintenance: BouncyCastle 1.86, Kotlin
  2.4.20, KSP 2.3.12, Android Gradle Plugin 8.12.3, and test-library
  updates. No user-facing behavior change.

## [1.7.1] - 2026-09-18

### Fixed

- New contacts created on the phone in the Proton account now sync up
  to Proton instead of silently failing with "contact not found
  locally."
- Editing a contact that was imported without a vCard UID now syncs
  back to Proton instead of failing. Such edits were rejected by the
  server (HTTP 400) because the update omitted the required UID.
- Failed-change reasons shown in Settings are now legible (e.g.
  "HTTP 400") instead of an obfuscated release-build class name.

## [1.7.0] - 2026-09-05

### Added

- The launcher and Settings screens now show a live "Sync in
  progress…" indicator while a sync is actually running — driven by
  the system sync framework, not just the button press. Settings also
  shows the previous sync's result (time and outcome, including
  per-contact failure counts) and keeps it on screen until the new
  run completes.

### Fixed

- A wrong or expired two-factor code during sign-in is now reported
  as "Wrong code. Try again." instead of the misleading "Could not
  reach Proton" connection error, and the code can be re-entered on
  the same screen. An invalidated sign-in session (HTTP 401) is
  reported as expired; only genuine transport failures show the
  connectivity message.

## [1.6.0] - 2026-08-29

### Fixed

- Contacts removed from the phone by another app (e.g. a
  duplicate-cleanup tool) are now restored from Proton on the next
  sync, even when the Proton contact itself hasn't changed. Sync also
  detects and removes duplicate copies of the same Proton contact
  inside the pcontacts account — contacts of other accounts are never
  touched — and repairs its bookkeeping when it points at the wrong
  local contact. Pending local deletions are still honoured and are
  never resurrected. (ADR-0022)

## [1.5.0] - 2026-08-05

### Added

- Russian, French, Spanish, and Simplified Chinese translations — both
  in-app and on the F-Droid listing (descriptions and release notes).

### Security

- BouncyCastle updated 1.84 → 1.85, picking up six OpenPGP (bcpg)
  hardening fixes on code paths pcontacts exercises when parsing
  server-supplied key material and contact cards — including
  CVE-2026-59649 (unbounded user-attribute allocation, DoS),
  CVE-2026-12817 (AEAD tag-validation bypass), and CVE-2026-59643
  (silently ignored inline-signature policy failures).

### Changed

- Toolchain: Kotlin 2.4.10, KSP 2.3.10, kotest 6.2.3, kover 0.9.9.

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

[2.0.0]: https://github.com/andreabenetton/pcontacts/releases/tag/v2.0.0
[1.7.2]: https://github.com/andreabenetton/pcontacts/releases/tag/v1.7.2
[1.7.1]: https://github.com/andreabenetton/pcontacts/releases/tag/v1.7.1
[1.7.0]: https://github.com/andreabenetton/pcontacts/releases/tag/v1.7.0
[1.6.0]: https://github.com/andreabenetton/pcontacts/releases/tag/v1.6.0
[1.5.0]: https://github.com/andreabenetton/pcontacts/releases/tag/v1.5.0
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
