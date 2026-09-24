# pcontacts

**Proton Mail contacts → Android system address book.**

A GPL-3.0 Android app that signs in to a Proton Mail account, decrypts the user's contacts client-side, and exposes them to `ContactsContract` so they appear in the system Contacts app (and any other app that reads contacts) the same way WhatsApp or Telegram surface their own contact directories.

## Status

**[v2.0.0](CHANGELOG.md#200---2026-09-22) released 2026-09-22.** **Validated against the live Proton production API** — full SRP handshake, token persistence, keyPassword derivation, multi-key contact decrypt (user + address keys), and logout all succeed. See [`docs/API_RESEARCH.md`](docs/API_RESEARCH.md) for protocol details.

What works in code (verified by unit tests + live integration test):

- SRP login (Proton's custom go-srp variant, not standard SRP-6a) with TOTP 2FA. Modulus signature verification against pinned Proton SRP signing key (ADR-0014).
- Per-card decrypt: CLEAR_TEXT / SIGNED / ENCRYPTED / ENCRYPTED_AND_SIGNED dispatch via `:core:proton-contacts`, integrated end-to-end against real BouncyCastle in `ContactDecryptBootstrapTest`.
- Full vCard projection: FN / N pieces / multiple EMAIL / multiple TEL / multiple ADR / ORG / NOTE / IMPP / inline PHOTO / CATEGORIES + LabelIDs → GroupMembership.
- Two-tier sync skip: server `ModifyTime` first (cheap, no fetch), then content hash (avoids no-op writes when ModifyTime bumps but content didn't change).
- **Bidirectional sync** (ADR-0017, ADR-0018): local edits pushed to Proton via a persistent outbox. Change detection reads DIRTY/DELETED flags from `ContactsContract`, computes content hashes to skip no-ops. Push-before-pull ordering. Per-field conflict detection with user-facing resolution UI. Soft-delete with 1-hour grace period and cancel support.
- Per-card encrypt + sign for write-back: `ContactSerializer` produces SIGNED (FN + UID) and ENCRYPTED_AND_SIGNED (all remaining fields) cards. Full encrypt→decrypt round-trip verified with real BouncyCastle keys.
- 401 → `/auth/refresh` → retry under a single-flight mutex; 429 → Fibonacci backoff (1s, 2s, 3s, 5s, 8s) honouring `Retry-After`; 9001 (human verification) surfaced as a typed exception that stops the sync framework from retrying.
- Logout: server-side revoke + ContactsContract wipe + Room mapping wipe + outbox wipe + `SecretStore.logout()` (zeroes secrets + deletes Keystore AEAD KEK alias) + Android Account removal.
- Periodic sync every 12h by default via `PeriodicSyncWorker` (NetworkType.CONNECTED + battery-not-low) plus the system `SyncAdapter`. The app's single screen shows a sync status card (health headline, progress, last-sync time, pending / failed / unverified / conflict rows with their dialogs), a stepped interval slider (1 / 6 / 12 / 24 h), the linked-contact import (ADR-0023), the lists of apps that hold `READ_CONTACTS`, and sign-in / sign-out.
- **One-way enrichment from other accounts** (ADR-0023): an in-app list of contacts that exist only in other providers (WhatsApp, Telegram, device-local, …) or whose Proton copy lacks details; the user picks what to copy into the Proton contact, or creates the Proton copy. Other apps' contacts are never modified.

### Known gaps

1. **Once contacts land in `ContactsContract`, the OS owns them.** Stock Android ships with pre-installed system applications (Google Play Services, Google Contacts, Gmail, the OEM dialer, the OEM messaging app, vendor "assistant" services, etc.) that are granted `READ_CONTACTS` by default or are very difficult to revoke. Decrypting Proton contacts onto a device with those apps still installed effectively shares them with Google and the OEM. **For a meaningful privacy posture, run pcontacts on a de-Googled ROM.** The app's own "De-Googled Android ROMs" screen (reached from the sign-in screen and from the OS-installed-apps list under Privacy) explains the term and lists the projects with what each does about Google services; being de-Googled says nothing about a ROM's security properties, which must be judged separately. The same content is in the repo as [`docs/DE_GOOGLED_ROMS.md`](docs/DE_GOOGLED_ROMS.md). pcontacts cannot fix this for you on stock Android; it is a property of the platform's permission model, not of this app. The Privacy section shows which installed apps hold `READ_CONTACTS` and links to the system page where the permission can be revoked.

2. **`x-pm-appversion` window drift.** The hardcoded version (`android-mail@3.0.12`) must stay within Proton's `2.0.0`–`3.0.12` acceptance window for the direct-`auth/info` login flow. It is a client identifier, not the latest app version — bumping it to newer android-mail releases breaks login. Requires occasional maintenance if the window shifts.

3. **Synced list mirrors Proton's address book — including auto-saved senders.** pcontacts pulls from `contacts/v4/contacts*` only (the same surface as Proton Mail web's Contacts page). If your Proton Mail **Auto-save contacts** setting is on (`mail.proton.me → Settings → Messages and composing → Automatically save contacts`), Proton silently adds every email sender to your address book and pcontacts faithfully syncs them. The API exposes no flag distinguishing manual contacts from auto-saved ones, so client-side filtering can't be done without risking real-contact loss. To trim the list, disable Auto-save and delete unwanted entries on the web; the next pcontacts sync mirrors the cleanup.

## FAQ

Practical questions from real phones, answered in [`docs/FAQ.md`](docs/FAQ.md):

- [A contact I saved from WhatsApp disappeared after signing out, or after the 2.0 update](docs/FAQ.md#a-contact-i-saved-from-whatsapp-disappeared-after-signing-out-or-after-the-20-update)
- [WhatsApp shows a phone number instead of the name](docs/FAQ.md#whatsapp-shows-a-phone-number-instead-of-the-name)
- [The Contacts app shows two or three entries for one person](docs/FAQ.md#the-contacts-app-shows-two-or-three-entries-for-one-person)
- [How do I make new contacts go to Proton?](docs/FAQ.md#how-do-i-make-new-contacts-go-to-proton)
- [What does sign-out delete, and what does it keep?](docs/FAQ.md#what-does-sign-out-delete-and-what-does-it-keep)
- [Why did 2.0 ask me to sign in again and download everything?](docs/FAQ.md#why-did-20-ask-me-to-sign-in-again-and-download-everything)
- [When does pcontacts show a notification?](docs/FAQ.md#when-does-pcontacts-show-a-notification)
- [I deleted a contact on a Samsung and pcontacts asks what to do](docs/FAQ.md#i-deleted-a-contact-on-a-samsung-and-pcontacts-asks-what-to-do)
- [The sync interval slider has an "Off" position. What does it do?](docs/FAQ.md#the-sync-interval-slider-has-an-off-position-what-does-it-do)
- [How can I see where a contact is stored?](docs/FAQ.md#how-can-i-see-where-a-contact-is-stored)

## Why this exists

- Proton Mail has no CardDAV, no official Android contacts client, and no documented public API for contacts.
- Proton Mail Bridge handles IMAP/SMTP only; it does **not** sync contacts.
- DAVx5 forks that target an imagined Proton CardDAV endpoint do not work.

Until Proton publishes a first-party solution, this app reverse-engineers the same HTTP API the official Proton web client uses (the [ProtonMail/WebClients](https://github.com/ProtonMail/WebClients) GPL-3.0 repository) and performs the OpenPGP decrypt step on-device.

## Disclaimer

- **Not affiliated with or endorsed by Proton AG.**
- Uses the Proton Mail web client's HTTP API, which is **not officially documented or supported for third-party use**. The API may change at any time without notice and may break this app.
- Operates only with credentials the user owns. Does not bypass captchas, rate limits, abuse protection, or any other Proton security control.
- All cryptography happens on-device. Decrypted contact data is never logged or transmitted off-device. Decrypted contacts are written only where the user asked for them: the system Contacts provider. pcontacts keeps no app-private plaintext copy; the only app-private derived data are content hashes and, for conflict detection, a per-contact last-known-server snapshot sealed under the device Keystore (ADR-0018). See [`docs/adr/0007-client-side-decryption-only.md`](docs/adr/0007-client-side-decryption-only.md) and [`docs/THREAT_MODEL.md`](docs/THREAT_MODEL.md).

## License

GPL-3.0-only. See [`LICENSE`](LICENSE).

This project studies and adapts code from [ProtonMail/WebClients](https://github.com/ProtonMail/WebClients) (also GPL-3.0). Attribution lives in [`NOTICE`](NOTICE).

## Architecture decisions

See [`docs/adr/README.md`](docs/adr/README.md) for the index of all ADRs.

The load-bearing calls:

- **Native Kotlin crypto** in `:core:crypto`: BouncyCastle for OpenPGP, ported Proton SRP (go-srp variant) + bcrypt-SHA-512. No embedded JS engine. (ADR 0002)
- **F-Droid first**, sideload-friendly. No Google Play Services, no telemetry, no closed-source binaries. Enforced by a `checkForbiddenDependencies` Gradle task that fails CI on any forbidden group landing in a release classpath. (ADRs 0003, 0015)
- **The dependency audit ships in the app.** A "Dependencies" chip next to the version opens a screen listing every shipped artifact with version, license and known advisories linked to osv.dev. The list is a snapshot generated at build time from one osv.dev query and committed to the repo; CI fails when it no longer matches the resolved classpath or when osv.dev or the weekly Dependency-Check scan reports an open advisory it does not list. (ADR 0024)
- **Optional runtime check, off by default.** A switch in Privacy sends the same artifact list (public in this repo) once a day to osv.dev, which then sees the device's address and can infer the app from the artifacts asked; the switch says so and calls it a privacy-versus-security trade-off left to the user. Only while it is on does the chip carry a verdict ("Dependencies OK"; assessed, amber; vulnerability, red), from osv.dev alone; new advisories are announced once and can be muted, which counts as assessed until the artifact changes version. Nothing else in the app depends on the answer. (ADR 0025)
- **`AbstractAccountAuthenticator` + `SyncAdapter`** for system integration; `WorkManager` as the belt-and-suspenders periodic scheduler. (ADR 0004)
- **Client-side decrypt only.** The app never calls `GET contacts/v4/contacts/export` (server-side decrypt); a CI grep fails on the path. (ADR 0007)
- **Bidirectional sync** with persistent outbox, per-field three-way merge, soft-delete with 1-hour grace, and push-before-pull ordering. Supersedes the read-only MVP scope. (ADRs 0017, 0018; supersedes ADR 0006)
- **Delete-and-reinsert child Data rows on update**, never the parent RawContact (preserves user-owned aggregate state — starred, custom ringtone, custom photo). (ADR 0010)
- **Modulus signature verification** against a pinned Proton SRP signing key. Verified against live API. (ADR 0014)

## Build & install

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/pcontacts-debug.apk
```

For a release build (R8 + minification, exercises every `proguard-rules.pro` keep rule):

```bash
./gradlew :app:assembleRelease
# app/build/outputs/apk/release/pcontacts-release.apk when signing properties are set,
# pcontacts-release-unsigned.apk otherwise (the CI reproducible-build path)
```

The Gradle wrapper pins the Gradle version; AGP and Kotlin versions live in `gradle/libs.versions.toml`. JDK 17 is required.

Reproducible-build verification is documented in [`docs/BUILD.md`](docs/BUILD.md) and enforced in CI via `diffoscope`.

## Vulnerability scanning

Three layers, documented in [`docs/BUILD.md` §Vulnerability scanning](docs/BUILD.md#vulnerability-scanning): an osv.dev audit snapshot committed with each release and verified by CI, an opt-in daily osv.dev check on the device, and OWASP Dependency-Check against the NVD on every push and weekly in CI, with a reviewed suppression file.

## Running the test suites

```bash
# Pure-JVM unit tests (fast, no emulator needed):
./gradlew :core:crypto:test \
          :core:proton-api:test \
          :core:proton-contacts:test \
          :core:sync:test \
          :feature:onboarding:test \
          :feature:settings:test

# Android-library tests via Robolectric (slower first run):
./gradlew :core:storage:test \
          :core:contacts-writer:test \
          :tools:lint:test

# Android lint on the debug build:
./gradlew :app:lintDebug

# ADR-0015 forbidden-dependency check:
./gradlew checkForbiddenDependencies

# Instrumented tests (requires connected device or emulator):
./gradlew :core:contacts-writer:connectedDebugAndroidTest \
          :feature:onboarding:connectedDebugAndroidTest \
          :feature:settings:connectedDebugAndroidTest
```

GitHub Actions runs all of the above plus `:app:assembleRelease` on every push / PR. Instrumented tests run on API 26 and 33 emulators.

## Threat model

See [`docs/THREAT_MODEL.md`](docs/THREAT_MODEL.md) for the STRIDE pass. Highlights:

- ContactsContract is shared by design — every app the user grants `READ_CONTACTS` to can read synced contacts. That's the point.
- Heap-memory exposure on rooted devices is an accepted residual risk; the JVM can't guarantee memory zeroization.
- SPKI certificate pins (ISRG Root X1 + X2) are enforced via OkHttp's `CertificatePinner`. Release builds gate on non-empty pins. SRP modulus signature verification provides a second TLS-independent layer.
- When Proton requires a captcha (Code 9001), the verification page (`verify.proton.me`) is loaded in an in-app `WebView` with JavaScript enabled. Navigation is restricted to `*.proton.me`, DOM storage / file / content access are disabled, and the only JS bridge call accepted is the success envelope from Proton's own page. The resulting verification token is stored in the Keystore-sealed `SecretStore` (ADR-0009) and attached to subsequent requests via the `x-pm-human-verification-token{,-type}` headers. The pattern mirrors `ProtonMail/protoncore_android`'s `HV3DialogFragment`; see [ADR-0019](docs/adr/0019-human-verification-webview-flow.md).

## Reporting a security issue

See `docs/THREAT_MODEL.md §7` and [`SECURITY.md`](SECURITY.md). Short version: email **&#97;&#110;&#100;&#114;&#101;&#97;&#46;&#98;&#101;&#110;&#101;&#116;&#116;&#111;&#110;&#64;&#98;&#108;&#117;&#101;&#116;&#101;&#97;&#109;&#46;&#101;&#101;** or open a private GitHub issue — NOT a public PR. Expect a 30-day coordinated-disclosure embargo from first acknowledgement.

## Contributing

Both the SRP auth flow and the bidirectional sync write path (CREATE / UPDATE / DELETE round-trip) are validated against the live Proton API via nightly canary tests. PRs welcome for:

- Review of the non-English translations (de, es, fr, it, ru, zh-CN) by native speakers.
- Additional OpenPGP test vector capture in `tools/vectors/capture.js`.
- Device reports for the "Default account for new contacts" and Contacts-permission shortcuts, which depend on OEM Settings intents.

Open an issue first for anything larger; this is a single-maintainer project and an unscoped PR is hard to absorb.

## Buy me a coffee

If pcontacts is useful to you, a coffee is welcome, in bitcoin:

`bc1qe793n6n6yvfyazu6wrt4upueljksfg8lep8x2p`
