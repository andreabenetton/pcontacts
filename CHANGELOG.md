<!--
  SPDX-License-Identifier: GPL-3.0-only
  SPDX-FileCopyrightText: 2026 pcontacts contributors
-->

# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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

[0.1.0]: https://github.com/andreabenetton/pcontacts/releases/tag/v0.1.0
