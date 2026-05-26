<!--
  SPDX-License-Identifier: GPL-3.0-only
  SPDX-FileCopyrightText: 2026 pcontacts contributors
-->

# Security policy

## Supported versions

Only the latest release on the `master` branch receives security
fixes. There is no backport policy at this stage.

## Reporting a vulnerability

**Do not open a public issue for security vulnerabilities.**

Report via either channel:

- **GitHub Security Advisory** — click "Report a vulnerability" under
  the repo's **Security** tab. This creates a private draft visible
  only to the maintainer.
- **Email** — **andrea.benetton@blueteam.ee** (also listed in the
  top-level `NOTICE` file).

In either case, include:

1. A description of the vulnerability and its impact.
2. Steps to reproduce or a proof of concept.
3. The version or commit hash affected.

You should receive an acknowledgement within 72 hours. Fixes for
confirmed vulnerabilities will be committed, tagged, and released
as soon as practical — typically within 7 days for critical issues.
Credit will be given in the release notes unless you prefer
otherwise.

## Scope

The following are in scope:

- Authentication and session handling (SRP, token storage, refresh).
- Cryptographic operations (OpenPGP decrypt/verify, bcrypt-SHA512,
  modulus signature verification).
- Secret storage (keyPassword, tokens, private key material).
- Data leakage (decrypted contact data in logs, backups, disk).
- ContactsContract write safety (duplicate injection, missing
  `caller_is_syncadapter`, data overwrites).
- Dependency supply-chain issues affecting the signed APK.

Out of scope:

- Proton's server-side infrastructure.
- Android OS or device-level vulnerabilities.
- Social engineering or phishing attacks.

## Threat model

See [docs/THREAT_MODEL.md](docs/THREAT_MODEL.md) for the full STRIDE
analysis.
