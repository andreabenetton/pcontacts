# pcontacts

**Proton Mail contacts → Android system address book.**

A GPL-3.0 Android app that signs in to a Proton Mail account, decrypts the user's contacts client-side, and exposes them to `ContactsContract` so they appear in the system Contacts app (and any other app that reads contacts) the same way WhatsApp or Telegram surface their own contact directories.

## Status

**v1.4.0 released.** **Validated against the live Proton production API** — full SRP handshake, token persistence, keyPassword derivation, multi-key contact decrypt (user + address keys), and logout all succeed. See [`docs/API_RESEARCH.md`](docs/API_RESEARCH.md) for protocol details.

What works in code (verified by unit tests + live integration test):

- SRP login (Proton's custom go-srp variant, not standard SRP-6a) with TOTP 2FA. Modulus signature verification against pinned Proton SRP signing key (ADR-0014).
- Per-card decrypt: CLEAR_TEXT / SIGNED / ENCRYPTED / ENCRYPTED_AND_SIGNED dispatch via `:core:proton-contacts`, integrated end-to-end against real BouncyCastle in `ContactDecryptBootstrapTest`.
- Full vCard projection: FN / N pieces / multiple EMAIL / multiple TEL / multiple ADR / ORG / NOTE / IMPP / inline PHOTO / CATEGORIES + LabelIDs → GroupMembership.
- Two-tier sync skip: server `ModifyTime` first (cheap, no fetch), then content hash (avoids no-op writes when ModifyTime bumps but content didn't change).
- **Bidirectional sync** (ADR-0017, ADR-0018): local edits pushed to Proton via a persistent outbox. Change detection reads DIRTY/DELETED flags from `ContactsContract`, computes content hashes to skip no-ops. Push-before-pull ordering. Per-field conflict detection with user-facing resolution UI. Soft-delete with 1-hour grace period and cancel support.
- Per-card encrypt + sign for write-back: `ContactSerializer` produces SIGNED (FN + UID) and ENCRYPTED_AND_SIGNED (all remaining fields) cards. Full encrypt→decrypt round-trip verified with real BouncyCastle keys.
- 401 → `/auth/refresh` → retry under a single-flight mutex; 429 → Fibonacci backoff (1s, 2s, 3s, 5s, 8s) honouring `Retry-After`; 9001 (human verification) surfaced as a typed exception that stops the sync framework from retrying.
- Logout: server-side revoke + ContactsContract wipe + Room mapping wipe + outbox wipe + `SecretStore.logout()` (zeroes secrets + deletes Keystore AEAD KEK alias) + Android Account removal.
- Periodic sync every 12h via `PeriodicSyncWorker` (NetworkType.CONNECTED + battery-not-low) plus the system `SyncAdapter`. Settings screen with sync interval selector, outbox status, pending-delete banner, and conflict resolution UI.

### Known gaps

1. **Once contacts land in `ContactsContract`, the OS owns them.** Stock Android ships with pre-installed system applications (Google Play Services, Google Contacts, Gmail, the OEM dialer, the OEM messaging app, vendor "assistant" services, etc.) that are granted `READ_CONTACTS` by default or are very difficult to revoke. Decrypting Proton contacts onto a device with those apps still installed effectively shares them with Google and the OEM. **For a meaningful privacy posture, run pcontacts on a de-Googled ROM such as [GrapheneOS](https://grapheneos.org/) (preferred — sandboxed Google Play, per-app contacts scopes) or [LineageOS for microG](https://lineage.microg.org/)** (or vanilla LineageOS without GApps). pcontacts cannot fix this for you on stock Android; it is a property of the platform's permission model, not of this app.

2. **`x-pm-appversion` window drift.** The hardcoded version (`android-mail@3.0.12`) must stay within Proton's `2.0.0`–`3.0.12` acceptance window for the direct-`auth/info` login flow. It is a client identifier, not the latest app version — bumping it to newer android-mail releases breaks login. Requires occasional maintenance if the window shifts.

3. **Synced list mirrors Proton's address book — including auto-saved senders.** pcontacts pulls from `contacts/v4/contacts*` only (the same surface as Proton Mail web's Contacts page). If your Proton Mail **Auto-save contacts** setting is on (`mail.proton.me → Settings → Messages and composing → Automatically save contacts`), Proton silently adds every email sender to your address book and pcontacts faithfully syncs them. The API exposes no flag distinguishing manual contacts from auto-saved ones, so client-side filtering can't be done without risking real-contact loss. To trim the list, disable Auto-save and delete unwanted entries on the web; the next pcontacts sync mirrors the cleanup.

## Why this exists

- Proton Mail has no CardDAV, no official Android contacts client, and no documented public API for contacts.
- Proton Mail Bridge handles IMAP/SMTP only; it does **not** sync contacts.
- DAVx5 forks that target an imagined Proton CardDAV endpoint do not work.

Until Proton publishes a first-party solution, this app reverse-engineers the same HTTP API the official Proton web client uses (the [ProtonMail/WebClients](https://github.com/ProtonMail/WebClients) GPL-3.0 repository) and performs the OpenPGP decrypt step on-device.

## Disclaimer

- **Not affiliated with or endorsed by Proton AG.**
- Uses the Proton Mail web client's HTTP API, which is **not officially documented or supported for third-party use**. The API may change at any time without notice and may break this app.
- Operates only with credentials the user owns. Does not bypass captchas, rate limits, abuse protection, or any other Proton security control.
- All cryptography happens on-device. Decrypted contact data is never logged, transmitted off-device, or persisted to disk. See [`docs/adr/0007-client-side-decryption-only.md`](docs/adr/0007-client-side-decryption-only.md) and [`docs/THREAT_MODEL.md`](docs/THREAT_MODEL.md).

## License

GPL-3.0-only. See [`LICENSE`](LICENSE).

This project studies and adapts code from [ProtonMail/WebClients](https://github.com/ProtonMail/WebClients) (also GPL-3.0). Attribution lives in [`NOTICE`](NOTICE).

## Architecture decisions

See [`docs/adr/README.md`](docs/adr/README.md) for the index of all ADRs.

The load-bearing calls:

- **Native Kotlin crypto** in `:core:crypto`: BouncyCastle for OpenPGP, ported Proton SRP (go-srp variant) + bcrypt-SHA-512. No embedded JS engine. (ADR 0002)
- **F-Droid first**, sideload-friendly. No Google Play Services, no telemetry, no closed-source binaries. Enforced by a `checkForbiddenDependencies` Gradle task that fails CI on any forbidden group landing in a release classpath. (ADRs 0003, 0015)
- **`AbstractAccountAuthenticator` + `SyncAdapter`** for system integration; `WorkManager` as the belt-and-suspenders periodic scheduler. (ADR 0004)
- **Client-side decrypt only.** The app never calls `GET contacts/v4/contacts/export` (server-side decrypt); a CI grep fails on the path. (ADR 0007)
- **Bidirectional sync** with persistent outbox, per-field three-way merge, soft-delete with 1-hour grace, and push-before-pull ordering. Supersedes the read-only MVP scope. (ADRs 0017, 0018; supersedes ADR 0006)
- **Delete-and-reinsert child Data rows on update**, never the parent RawContact (preserves user-owned aggregate state — starred, custom ringtone, custom photo). (ADR 0010)
- **Modulus signature verification** against a pinned Proton SRP signing key. Verified against live API. (ADR 0014)

## Build & install

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

For a release build (R8 + minification, exercises every `proguard-rules.pro` keep rule):

```bash
./gradlew :app:assembleRelease
# unsigned APK at app/build/outputs/apk/release/app-release-unsigned.apk
```

The Gradle wrapper bootstraps Gradle 8.10.2 + AGP 8.7.0 + Kotlin 2.0.21. JDK 17 is required.

Reproducible-build verification is documented in [`docs/BUILD.md`](docs/BUILD.md) and enforced in CI via `diffoscope`.

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
./gradlew :core:contacts-writer:connectedDebugAndroidTest
```

GitHub Actions runs all of the above plus `:app:assembleRelease` on every push / PR. Instrumented tests run on API 26 and 33 emulators.

## Threat model

See [`docs/THREAT_MODEL.md`](docs/THREAT_MODEL.md) for the STRIDE pass. Highlights:

- ContactsContract is shared by design — every app the user grants `READ_CONTACTS` to can read synced contacts. That's the point.
- Heap-memory exposure on rooted devices is an accepted residual risk; the JVM can't guarantee memory zeroization.
- SPKI certificate pins (ISRG Root X1 + X2) are enforced via OkHttp's `CertificatePinner`. Release builds gate on non-empty pins. SRP modulus signature verification provides a second TLS-independent layer.
- When Proton requires a captcha (Code 9001), the verification page (`verify.proton.me`) is loaded in an in-app `WebView` with JavaScript enabled. Navigation is restricted to `*.proton.me`, DOM storage / file / content access are disabled, and the only JS bridge call accepted is the success envelope from Proton's own page. The resulting verification token is stored in `EncryptedSharedPreferences` and attached to subsequent requests via the `x-pm-human-verification-token{,-type}` headers. The pattern mirrors `ProtonMail/protoncore_android`'s `HV3DialogFragment`; see [ADR-0019](docs/adr/0019-human-verification-webview-flow.md).

## Contributing

Both the SRP auth flow and the bidirectional sync write path (CREATE / UPDATE / DELETE round-trip) are validated against the live Proton API via nightly canary tests. PRs welcome for:

- Compose UI tests for the login + settings screens.
- Additional OpenPGP test vector capture in `tools/vectors/capture.js`.

Open an issue first for anything larger; this is a single-maintainer project and an unscoped PR is hard to absorb.

## Reporting a security issue

See `docs/THREAT_MODEL.md §7` and [`SECURITY.md`](SECURITY.md). Short version: email **andrea.benetton@blueteam.ee** or open a private GitHub issue — NOT a public PR. Expect a 30-day coordinated-disclosure embargo from first acknowledgement.
