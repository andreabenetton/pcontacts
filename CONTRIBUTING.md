<!--
  SPDX-License-Identifier: GPL-3.0-only
  SPDX-FileCopyrightText: 2026 pcontacts contributors
-->

# Contributing to pcontacts

Thank you for considering a contribution. This project is
GPL-3.0-only; every contribution must be compatible with that
license.

## Getting started

See [docs/BUILD.md](docs/BUILD.md) for build prerequisites and
instructions.

## Reporting bugs

Open a GitHub issue using the **Bug report** template. Include:

- Android version and device model.
- Steps to reproduce.
- Expected vs. actual behavior.
- Logcat output (redact any tokens or contact data).

For security vulnerabilities, see [SECURITY.md](SECURITY.md).

## Proposing changes

1. Open an issue describing the change before writing code.
2. Fork the repo and create a branch from `master`.
3. Keep commits small, scoped, and independently reviewable.
4. Every source file must carry the SPDX header:
   ```
   // SPDX-License-Identifier: GPL-3.0-only
   // SPDX-FileCopyrightText: 2026 pcontacts contributors
   ```
5. Run `./gradlew assembleDebug test detekt` and fix any failures.
6. Open a pull request against `master`.

## Code style

- Kotlin, formatted per `kotlin.code.style=official`.
- No `android.util.Log` or `println` in `:core:*` or `:feature:*`
  modules. Use the `Logger` interface from `:core:logging`.
- No decrypted contact data in log messages, exception text, or
  persisted state.

## Architecture decisions

Load-bearing decisions live in `docs/adr/`. If your change conflicts
with an existing ADR, write a new superseding ADR in the same PR.

## Dependencies

New runtime dependencies must be GPL-3.0-compatible and must not
pull in Google Play Services, Firebase, or analytics SDKs. The CI
license gate enforces this automatically.
