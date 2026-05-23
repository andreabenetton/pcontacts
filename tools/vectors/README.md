<!--
  SPDX-License-Identifier: GPL-3.0-only
  SPDX-FileCopyrightText: 2026 pcontacts contributors
-->

# Proton crypto vector capture (plan §17 task 9)

The Kotlin port in `:core:crypto` (bcrypt-SHA-512, SRP-6a, OpenPGP)
has `[A]`-marked assumptions about Proton-specific parameters that
can only be flipped to `[V]` by comparing against
`@protontech/crypto`'s actual output.

This directory hosts the Node.js capture script that produces a
JSON file of known-good inputs + expected outputs. The Kotlin
`CapturedVectorsTest` then loads the JSON from the classpath and
asserts our implementations match.

## Prerequisites

- Node.js 18+ (anything that resolves modern `@protontech/crypto`).
- `npm install` (pulls `@protontech/crypto` from npmjs.org).

## Running

```bash
cd tools/vectors
npm install
node capture.js
```

Output lands at:

```
core/crypto/src/test/resources/proton-crypto-vectors.json
```

Commit that file alongside any change to `capture.js` so CI sees
the same vectors developers run against.

## When to re-run

- After `@protontech/crypto` bumps (their format / cost factor
  may change between major versions).
- After adding a new vector class to `capture.js` (new input set,
  new operation type).
- Never just to "refresh" — the file is reproducible from the
  inputs in the script, so the only reason to regenerate is when
  the inputs or the underlying crypto changes.

## Current coverage

The committed script captures bcrypt-SHA-512 vectors (3 inputs:
ASCII short, ASCII long, UTF-8 unicode) over a 16-byte salt. SRP
and OpenPGP captures are stubbed — the `@protontech/crypto`
surface for those two areas needs a version-specific adapter
that lands when the maintainer runs the script against a known
version + adds the right export lookups.

## Why this script isn't in CI

The script needs network access (`npm install`) and JavaScript
runtime. CI runs Gradle + JVM only. Run the script locally + commit
the JSON; CI then validates the Kotlin port against the committed
JSON via `CapturedVectorsTest`.

## What the script does NOT do

- Run against a live Proton account — vectors are derived purely
  from the `@protontech/crypto` package's own output. Validating
  against a real account is a separate task that needs the
  account credentials + a network path.
- Replace the SRP / OpenPGP unit tests already in `:core:crypto`
  — those continue to verify against RFC 5054 + synthetic inputs.
  This script's vectors are an *additional* layer that pins the
  Proton-specific behavior.
