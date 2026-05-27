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

## Next

- [x] Live write-path validation (nightly canary)
- [ ] Multi-account support
- [ ] FIDO2/WebAuthn 2FA
- [ ] Encrypted offline cache (SQLCipher)
- [ ] Compose UI polish
- [ ] F-Droid submission
- [x] 1.0 release
