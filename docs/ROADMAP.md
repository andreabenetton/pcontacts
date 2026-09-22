<!--
  SPDX-License-Identifier: GPL-3.0-only
  SPDX-FileCopyrightText: 2026 pcontacts contributors
-->

# Roadmap

> This is the public-facing subset of the internal plan. Open an issue before starting work on any unchecked item.

## Done (v1.0.0)

- [x] Phase 0: GPL-3.0 repo bootstrap
- [x] Phase 1-2: Account authenticator + SRP login + TOTP 2FA
- [x] Phase 3-6: Read-only sync (decrypt, vCard mapping, ContactsContract writes)
- [x] Phase 7: Incremental sync (ModifyTime + content hash)
- [x] Phase 8: Delete/merge handling
- [x] Phase 9: Bidirectional sync (persistent outbox, three-way merge, soft-delete)
- [x] Phase 10 (partial): R8/ProGuard hardened, reproducible build CI, OWASP dep-check

## Done (v1.3 – v1.7, 2026-07 → 2026-09)

- [x] F-Droid submission — [fdroiddata MR !39186](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/39186)
      merged 2026-08-01; [live on f-droid.org](https://f-droid.org/packages/io.pcontacts.app/)
      since v1.3.4. New releases flow automatically from signed tags
      (`AutoUpdateMode: Version`, `UpdateCheckMode: Tags`)
- [x] Live write-path validation (nightly canary)
- [x] Translations: German and Italian (v1.0.0), then Russian, French, Spanish and Simplified Chinese (v1.5.0)
- [x] Self-healing sync metadata when another app removes our rows (ADR-0022, v1.6.0)
- [x] Live sync-progress indicator; clearer two-factor and failed-change errors (v1.7.x)
- [x] Human-verification (captcha) handled in a locked-down in-app WebView (ADR-0019)

## Done (v2.0.0, 2026-09-22) — see [CHANGELOG](../CHANGELOG.md#200---2026-09-22)

- [x] Compose UI polish: one root screen, app bar with icon and version,
      sync status card with real states, stepped sync interval
- [x] Import details from other apps' contacts into Proton, one-way (ADR-0023)
- [x] Contact-access transparency: which apps hold `READ_CONTACTS`, with the
      system permission page one tap away
- [x] "De-Googled Android ROMs" guide in the app and as
      [`docs/DE_GOOGLED_ROMS.md`](DE_GOOGLED_ROMS.md)
- [x] Three-way merge on a persisted, Keystore-sealed base; resolvable
      conflicts; one live outbox row per contact (ADR-0017/0018 amendments)
- [x] Android's sync switch honoured by background syncs (ADR-0004 amendment)
- [x] Secrets sealed by the Android Keystore directly, durable sign-out,
      `security-crypto` dropped (ADR-0009 amendment); one-time re-login from 1.x
- [x] Sync-adapter and authenticator services not exported; verification
      WebView pinned to `verify.proton.me`; "Send via Proton Mail" validates its caller
- [x] CI actions pinned to commit SHAs, protected `release` environment,
      emulator matrix API 26 / 33 / 35

## Next

- [ ] Multi-account support
- [ ] FIDO2/WebAuthn 2FA
