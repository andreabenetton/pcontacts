# pcontacts

**Proton Mail contacts ↔ Android system address book.**

pcontacts is a GPL-3.0 Android app that connects your Proton Mail contacts to Android's system address book, so they appear in the Contacts app and in every other app that uses Android contacts. It talks to the same API as Proton's web client and does all the cryptography on the phone.

## Features

- **Two-way sync** between Proton Mail and Android: contacts appear as ordinary Android contacts, and changes made on the phone go back to Proton.
- Names, several email addresses and phone numbers, addresses, organisation, notes, birthday and anniversary, nickname, website and photo; Proton's contact groups become Android groups.
- **On-device encryption and decryption**: decrypted contacts never leave the phone through pcontacts.
- Proton sign-in with **two-factor authentication** (authenticator code or recovery code) and Proton's human verification.
- Automatic sync at an interval you choose (1, 3, 6, 12 or 24 hours, or off), and a Sync now button.
- **Conflicts** — the same contact changed on both sides — are merged when they touch different fields and otherwise left to you to settle.
- **Safe deletions**: a contact deleted on the phone reaches Proton only after a one-hour grace period, during which it can be cancelled.
- **Import from other apps**: copy phone numbers and other details that WhatsApp, Telegram, Signal or the phone itself keep for a person into the Proton contact, or create the Proton contact; contacts that Android would delete (saved under no account) can be moved into Proton instead.
- Shows which installed apps can read your contacts.
- No Google Play Services, no telemetry, no closed-source dependencies; the list of shipped dependencies and their known advisories is in the app.

## Status

**Latest release: [v2.2.0](CHANGELOG.md#220---2026-10-02)**, released 2 October 2026.

Sign-in, decryption, two-way sync and the Android contacts integration are validated against Proton's production API, and a daily canary run checks that the API still behaves as expected.

- [Changelog](CHANGELOG.md) — what each release changed
- [Roadmap](docs/ROADMAP.md) — what is planned
- [Known limitations](#known-limitations)

## Known limitations

### Android shares contacts with apps that may read them

Once contacts are in Android's address book, every app allowed to read contacts can read them. On stock Android that includes pre-installed Google and manufacturer apps, which hold the permission by default or make it hard to revoke. pcontacts cannot change this; it is how Android works. If the point is to keep your Proton contacts away from Google, run pcontacts on a de-Googled Android: see [De-Googled Android ROMs](docs/DE_GOOGLED_ROMS.md) (the same explanation is in the app) and the [threat model](docs/THREAT_MODEL.md). The app's Privacy section lists the apps that can read contacts and links to where the permission can be revoked.

### Proton's API is not a public API

pcontacts uses the API of Proton's own web client, which Proton does not document or support for third-party apps. Proton may change it at any time, and pcontacts will then need an update. Protocol details are in [API research](docs/API_RESEARCH.md).

### Auto-saved senders sync too

pcontacts mirrors your Proton address book. If Proton Mail's **Automatically save contacts** setting is on (`mail.proton.me → Settings → Messages and composing`), Proton adds people you exchange mail with to the address book on its own, and pcontacts syncs them as well. Proton does not mark auto-saved contacts, so pcontacts cannot tell them apart from the ones you created. To trim the list, turn the setting off and delete the unwanted contacts on the web; the next sync follows.

## FAQ

Practical questions from real phones, answered in [`docs/FAQ.md`](docs/FAQ.md):

- [A contact I saved from WhatsApp disappeared after signing out, or after the 2.0 update](docs/FAQ.md#a-contact-i-saved-from-whatsapp-disappeared-after-signing-out-or-after-the-20-update)
- [WhatsApp shows a phone number instead of the name](docs/FAQ.md#whatsapp-shows-a-phone-number-instead-of-the-name)
- [The Contacts app shows two or three entries for one person](docs/FAQ.md#the-contacts-app-shows-two-or-three-entries-for-one-person)
- [How do I make new contacts go to Proton?](docs/FAQ.md#how-do-i-make-new-contacts-go-to-proton)
- [What does sign-out delete, and what does it keep?](docs/FAQ.md#what-does-sign-out-delete-and-what-does-it-keep)
- [Why did 2.0 ask me to sign in again and download everything?](docs/FAQ.md#why-did-20-ask-me-to-sign-in-again-and-download-everything)
- [I turned on two-password mode (or changed my password) and pcontacts asks me to sign in again](docs/FAQ.md#i-turned-on-two-password-mode-or-changed-my-password-and-pcontacts-asks-me-to-sign-in-again)
- [When does pcontacts show a notification?](docs/FAQ.md#when-does-pcontacts-show-a-notification)
- [I deleted a contact on a Samsung and pcontacts asks what to do](docs/FAQ.md#i-deleted-a-contact-on-a-samsung-and-pcontacts-asks-what-to-do)
- [The sync interval slider has an "Off" position. What does it do?](docs/FAQ.md#the-sync-interval-slider-has-an-off-position-what-does-it-do)
- [How can I see where a contact is stored?](docs/FAQ.md#how-can-i-see-where-a-contact-is-stored)

## Why this exists

- Proton Mail has no CardDAV, no official Android contacts client, and no documented public API for contacts.
- Proton Mail Bridge handles IMAP/SMTP only; it does **not** sync contacts.
- DAVx5 forks that target an imagined Proton CardDAV endpoint do not work.

Until Proton publishes a first-party solution, pcontacts uses the same HTTP API as the official Proton web client (the [ProtonMail/WebClients](https://github.com/ProtonMail/WebClients) GPL-3.0 repository) and performs the OpenPGP decryption on the device.

## Privacy and security

- All cryptography happens on the device. Decrypted contacts are never logged and never sent anywhere by pcontacts; they are written only to Android's address book, where you asked for them. pcontacts keeps no plaintext copy of its own: only content hashes and, for conflict detection, a per-contact snapshot of Proton's last version, sealed with the device's hardware-backed key store.
- Sign-in follows Proton's own protocol, including its signature check on the server's login parameters; connections to Proton use pinned certificates.
- When Proton asks for a captcha, its own page is shown in a locked-down in-app browser restricted to `proton.me`.

The full analysis is the [threat model](docs/THREAT_MODEL.md).

**Reporting a security issue:** email **&#97;&#110;&#100;&#114;&#101;&#97;&#46;&#98;&#101;&#110;&#101;&#116;&#116;&#111;&#110;&#64;&#98;&#108;&#117;&#101;&#116;&#101;&#97;&#109;&#46;&#101;&#101;** or open a private GitHub security advisory — not a public issue or PR. See [`SECURITY.md`](SECURITY.md); expect a 30-day coordinated-disclosure embargo from first acknowledgement.

## Build and development

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/pcontacts-debug.apk
```

JDK 17 is required. Technical documentation:

- [Build, release and reproducible builds](docs/BUILD.md), including the test suites and vulnerability scanning
- [Architecture](docs/ARCHITECTURE.md) and its key decisions
- [Architecture Decision Records](docs/adr/README.md)
- [Threat model](docs/THREAT_MODEL.md)
- [API research](docs/API_RESEARCH.md)

## Contributing

PRs are welcome for:

- Review of the non-English translations (de, es, fr, it, ru, zh-CN) by native speakers.
- Additional OpenPGP test vector capture in `tools/vectors/capture.js`.
- Device reports for the "Default account for new contacts" and Contacts-permission shortcuts, which depend on OEM Settings intents.

Open an issue first for anything larger; this is a single-maintainer project and an unscoped PR is hard to absorb.

## Disclaimer

- **Not affiliated with or endorsed by Proton AG.**
- Uses the Proton Mail web client's HTTP API, which is **not officially documented or supported for third-party use**. The API may change at any time without notice and may break this app.
- Operates only with credentials the user owns. Does not bypass captchas, rate limits, abuse protection, or any other Proton security control.

## License

GPL-3.0-only. See [`LICENSE`](LICENSE).

This project studies and adapts code from [ProtonMail/WebClients](https://github.com/ProtonMail/WebClients) (also GPL-3.0). Attribution lives in [`NOTICE`](NOTICE).

## Buy me a coffee

If pcontacts is useful to you, a coffee is welcome, in bitcoin:

`bc1qe793n6n6yvfyazu6wrt4upueljksfg8lep8x2p`
