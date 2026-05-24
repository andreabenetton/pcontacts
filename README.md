# pcontacts

**Proton Mail contacts → Android system address book.**

A GPL-3.0 Android app that signs in to a Proton Mail account, decrypts the user's contacts client-side, and exposes them to `ContactsContract` so they appear in the system Contacts app (and any other app that reads contacts) the same way WhatsApp or Telegram surface their own contact directories.

## Status

Pre-release. The full plan §17 task list (1–20) is implemented in source and exercised by unit tests; plan phase 10 hardening (refresh mutex, 429 backoff, 9001 handling, ProGuard + R8, CI, threat model, license-check task) is also in. **Not yet validated against a live Proton account on a real device** — see [Known gaps](#known-gaps) before relying on this.

What works in code (verified by unit tests + assembleRelease):

- SRP-6a login with TOTP 2FA. Modulus signature verification machinery wired (pinned key resource still required — see [Known gaps](#known-gaps)).
- Per-card decrypt: CLEAR_TEXT / SIGNED / ENCRYPTED / ENCRYPTED_AND_SIGNED dispatch via `:core:proton-contacts`, integrated end-to-end against real BouncyCastle in `ContactDecryptBootstrapTest`.
- Full vCard projection: FN / N pieces / multiple EMAIL / multiple TEL / multiple ADR / ORG / NOTE / IMPP / inline PHOTO / CATEGORIES + LabelIDs → GroupMembership.
- Two-tier sync skip: server `ModifyTime` first (cheap, no fetch), then content hash (avoids no-op writes when ModifyTime bumps but content didn't change).
- 401 → `/auth/refresh` → retry under a single-flight mutex; 429 → Fibonacci backoff (1s, 2s, 3s, 5s, 8s) honouring `Retry-After`; 9001 (human verification) surfaced as a typed exception that stops the sync framework from retrying.
- Logout: server-side revoke + ContactsContract wipe + Room mapping wipe + `SecretStore.logout()` (zeroes secrets + deletes Keystore AEAD KEK alias) + Android Account removal.
- Periodic sync every 12h via `PeriodicSyncWorker` (NetworkType.CONNECTED + battery-not-low) plus the system `SyncAdapter`. "Sync now" + "Sign out" UI in the in-app Settings screen.

### Known gaps

1. **No live-account validation yet.** Every Proton-protocol claim past `[V]` markers in the code is a `[U]` (Unverified) or `[A]` (Assumption) until someone runs the APK against a real Proton account. See `docs/adr/0013-crypto-test-vectors.md` and `docs/adr/0014-modulus-pinning.md`.
2. **Pinned Proton SRP signing key resource is absent.** The verifier in `:core:crypto` is wired (`BouncyCastleProtonModulusVerifier`); when it can't find `/proton_srp_signing_key.asc` on the classpath it returns `NO_SIGNER_KEY` and the login orchestrator logs a warning and proceeds. Source + drop the key per the README at `core/crypto/src/main/resources/README_proton_srp_signing_key.md`, then flip the orchestrator's `NO_SIGNER_KEY` policy from warn-and-proceed to abort. Until that lands, the modulus is trusted by virtue of TLS only.
3. **SPKI certificate pins for `api.proton.me` are absent.** Same shape: pinner is wired, resource file empty, README at `core/proton-api/src/main/resources/README_proton_certificate_pins.md` documents the openssl one-liner to capture pins.
4. **`@protontech/crypto` vectors not captured.** `tools/vectors/capture.js` is ready to run; `CapturedVectorsTest` JUnit-Assumes its way to a no-op when the JSON isn't there. Once you run the script and commit the JSON, bcrypt-SHA-512 (and, after extending the script, SRP + OpenPGP) get pinned bit-exact against the reference implementation.
5. **No instrumented tests.** `ContactsContract` round-trip semantics (aggregation, tombstones, photo round-trip) are validated by Robolectric structural tests only. Add an emulator-backed pipeline before claiming end-to-end correctness.
6. **No reproducible-build CI gate.** ADR-0003 calls for diffoscope verification; not wired yet.

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

See [`docs/adr/README.md`](docs/adr/README.md) for the index of all 15 ADRs.

The load-bearing calls:

- **Native Kotlin crypto** in `:core:crypto`: BouncyCastle for OpenPGP, ported SRP-6a + bcrypt-SHA-512. No embedded JS engine. (ADR 0002)
- **F-Droid first**, sideload-friendly. No Google Play Services, no telemetry, no closed-source binaries. Enforced by a `checkForbiddenDependencies` Gradle task that fails CI on any forbidden group landing in a release classpath. (ADRs 0003, 0015)
- **`AbstractAccountAuthenticator` + `SyncAdapter`** for system integration; `WorkManager` as the belt-and-suspenders periodic scheduler. (ADR 0004)
- **Client-side decrypt only.** The app never calls `GET contacts/v4/contacts/export` (server-side decrypt); a CI grep fails on the path. (ADR 0007)
- **Read-only, single-account MVP.** Bidirectional write-back is plan phase 9 work. (ADR 0006)
- **Delete-and-reinsert child Data rows on update**, never the parent RawContact (preserves user-owned aggregate state — starred, custom ringtone, custom photo). (ADR 0010)
- **Modulus signature verification** against a pinned Proton SRP signing key (machinery shipped, pinned key resource pending — see Known gaps). (ADR 0014)

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

The Gradle wrapper bootstraps Gradle 8.10.2 + AGP 8.7.0 + Kotlin 2.0.21. JDK 17 required.

Reproducible-build instructions will land in `docs/BUILD.md` when the diffoscope CI gate is wired.

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
```

GitHub Actions runs all three plus `:app:assembleRelease` on every push / PR.

## Threat model

See [`docs/THREAT_MODEL.md`](docs/THREAT_MODEL.md) for the STRIDE pass. Highlights:

- ContactsContract is shared by design — every app the user grants `READ_CONTACTS` to can read synced contacts. That's the point.
- Heap-memory exposure on rooted devices is an accepted residual risk; the JVM can't guarantee memory zeroization.
- Until the pinned signing key + SPKI cert pins land (see Known gaps), modulus + transport security depend on Android's system trust store.

## Contributing

The API surface is still being validated against a live account; until that's done, the architecture decisions in `docs/adr/` are the firmest part of the project. PRs welcome for:

- Capturing `@protontech/crypto` vectors via `tools/vectors/capture.js` and committing the resulting JSON.
- Sourcing + pinning the Proton SRP signing key (`docs/adr/0014-modulus-pinning.md`).
- Sourcing + pinning SPKI certificates for `api.proton.me`.
- Instrumented `ContactsContract` round-trip tests on an emulator pipeline.
- Compose UI tests for the login + settings screens.

Open an issue first for anything larger; this is a single-maintainer project and an unscoped PR is hard to absorb.

## Reporting a security issue

See `docs/THREAT_MODEL.md §7`. Short version: private issue or maintainer email, NOT a public PR. Expect a 30-day coordinated-disclosure embargo from first acknowledgement.
