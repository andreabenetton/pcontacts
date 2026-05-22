# pcontacts

**Proton Mail contacts → Android system address book.**

A GPL-3.0 Android app that signs in to a Proton Mail account, decrypts the user's contacts client-side, and exposes them to `ContactsContract` so they appear in the system Contacts app (and any other app that reads contacts) the same way WhatsApp or Telegram surface their own contact directories.

## Status

Pre-implementation. The architecture is captured in [`docs/adr/`](docs/adr/). The full execution plan (phases, roadmap, risk register) lives outside this repository in the planning notes that produced this scaffold.

Nothing in `app/` or `core/` exists yet. The first 20 implementation tasks are listed in the corresponding plan; ADRs 0001–0015 are the architecture they rest on.

## Why this exists

- Proton Mail has no CardDAV, no official Android contacts client, and no documented public API for contacts.
- Proton Mail Bridge handles IMAP/SMTP only; it does **not** sync contacts.
- DAVx5 forks that target an imagined Proton CardDAV endpoint do not work.

Until Proton publishes a first-party solution, this app reverse-engineers the same HTTP API the official Proton web client uses (the [ProtonMail/WebClients](https://github.com/ProtonMail/WebClients) GPL-3.0 repository) and performs the OpenPGP decrypt step on-device.

## Disclaimer

- **Not affiliated with or endorsed by Proton AG.**
- Uses the Proton Mail web client's HTTP API, which is **not officially documented or supported for third-party use**. The API may change at any time without notice and may break this app.
- Operates only with credentials the user owns. Does not bypass captchas, rate limits, abuse protection, or any other Proton security control.
- All cryptography happens on-device. Decrypted contact data is never logged, transmitted off-device, or persisted to disk in the MVP. See [`docs/adr/0007-client-side-decryption-only.md`](docs/adr/0007-client-side-decryption-only.md) and the (forthcoming) `docs/THREAT_MODEL.md`.

## License

GPL-3.0-only. See [`LICENSE`](LICENSE).

This project studies and adapts code from [ProtonMail/WebClients](https://github.com/ProtonMail/WebClients) (also GPL-3.0). Attribution lives in [`NOTICE`](NOTICE).

## Architecture decisions

See [`docs/adr/README.md`](docs/adr/README.md) for the index.

The headline calls:

- Native Kotlin crypto: BouncyCastle for OpenPGP, a ported SRP-6a + bcrypt-SHA512 in `:core:crypto`. No embedded JS engine. (ADR 0002)
- F-Droid first, sideload-friendly. No Google Play Services, no telemetry, no closed-source binaries. (ADRs 0003, 0015)
- `AbstractAccountAuthenticator` + `SyncAdapter` for system integration; WorkManager as the belt-and-suspenders periodic scheduler. (ADR 0004)
- Read-only, single-account MVP. Bidirectional sync deferred to phase 9. (ADR 0006)

## Build & install

Not yet buildable. The scaffold lands in the next set of commits per the plan.

When it does build:

```
./gradlew :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

Reproducible-build instructions will live in `docs/BUILD.md`.

## Contributing

Not yet open for contributions; the API surface is still being validated against a live account. Once a `v0.1.0-mvp` tag exists, see `CONTRIBUTING.md`.
