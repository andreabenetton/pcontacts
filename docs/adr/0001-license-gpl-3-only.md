# ADR-0001: License — GPL-3.0-only

- **Status:** Accepted
- **Date:** 2026-05-22
- **Deciders:** project owner
- **Related:** ADR-0015

## Context

The project derives from `ProtonMail/WebClients`, which is licensed GPL-3.0-or-later. Studying and adapting Proton's TypeScript contacts/auth/crypto modules to produce a Kotlin/Android port is a derivative work in the copyleft sense. We must pick a license that is:

1. Compatible with GPL-3.0 source we adapt.
2. Compatible with our chosen runtime dependencies — BouncyCastle (MIT), ez-vcard (BSD), AndroidX (Apache 2.0), kotlinx (Apache 2.0). All are GPL-3.0 compatible.
3. Acceptable to F-Droid for inclusion as a non-anti-feature build (see ADR-0003).
4. Aligned with the spirit of the project: an audit-friendly, user-controlled alternative to a missing first-party Android Proton contacts client.

GPL-3.0-only vs GPL-3.0-or-later: we want forks to make a deliberate choice about future GPL versions rather than auto-inheriting future drafts whose terms we cannot evaluate today.

## Decision

License the entire repository as **GPL-3.0-only** (SPDX identifier `GPL-3.0-only`). The `LICENSE` file at the repository root contains the verbatim GPL-3.0 text from gnu.org.

Every source file carries an SPDX header:

```
// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors
```

Files materially derived from `ProtonMail/WebClients` carry an additional `SPDX-FileCopyrightText` line attributing Proton AG and naming the upstream file, as defined in `NOTICE`.

## Alternatives considered

- **GPL-3.0-or-later.** Rejected to prevent automatic adoption of future GPL versions whose text we have not reviewed.
- **AGPL-3.0.** Rejected — the app is client-side software, not a network service; AGPL adds no protection and reduces compatibility.
- **MIT / Apache 2.0.** Rejected — incompatible with the GPL-3.0 nature of the WebClients code we adapt.
- **Dual license.** Rejected as needless complexity for a single-author scaffold.

## Consequences

- All downstream forks, distributions, and APKs must ship matching corresponding source.
- F-Droid inclusion path is straightforward (no anti-features from licensing).
- Closed-source contributions are not accepted.
- Any future dependency must be GPL-3.0-compatible. The Gradle build will fail release assembly if a transitively pulled artifact carries an incompatible license (enforced by a license-compatibility check, see ADR-0015).
- Releasing on Google Play remains legally possible but requires care around Play's permissions and policies — see ADR-0003 for why we're not pursuing it.

## Validation

- `LICENSE` SHA matches the canonical GPL-3.0 text at `https://www.gnu.org/licenses/gpl-3.0.txt`.
- CI license-check task passes (added in the build-system commit).
- Every source file carries an SPDX header (CI lint check).
