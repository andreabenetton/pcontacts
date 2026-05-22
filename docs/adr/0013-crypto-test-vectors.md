# ADR-0013: Crypto correctness — capture vectors from `@protontech/crypto` once

- **Status:** Accepted
- **Date:** 2026-05-22
- **Deciders:** project owner
- **Related:** ADR-0002, ADR-0014

## Context

ADR-0002 commits to porting SRP-6a and bcrypt-SHA512 to Kotlin and implementing OpenPGP via BouncyCastle. The single largest risk is that our implementations are not bit-exact with Proton's web client:

- SRP-6a has implementation choices (padding length, hash domain separation, `k` constant derivation) that RFC 5054 leaves ambiguous; different libraries diverge subtly.
- bcrypt-SHA512 is `bcrypt(SHA-512(password))` per Proton's published spec `[A]` — but cost factor, salt encoding, and output encoding all need verification.
- OpenPGP canonicalization: the web client passes `stripTrailingSpaces: true` to `verifyMessage`, suggesting `CANONICAL_TEXT_DOCUMENT` mode with trailing-whitespace stripping. BouncyCastle's behavior here is well-defined but easy to misconfigure.
- The vCard-byte stream that gets signed/verified must be canonicalized identically on both ends.

Without verification, the first time we try to log in to a real account, we may produce a `ServerProof` mismatch — or worse, log in successfully but fail to decrypt any contact, leaving the user stranded.

The simplest mitigation is to generate test vectors **once** from a known-correct source: a small Node script that uses `@protontech/crypto` (the published Proton package, which is open source under GPL-3.0) to:

- Compute SRP triples (`ClientEphemeral`, `ClientProof`, `expectedServerProof`) for a fixed set of `(password, salt, Modulus, ServerEphemeral, Version, SRPSession)` inputs.
- Compute `keyPassword = bcrypt-SHA512(password, KeySalt)` for a fixed set of `(password, KeySalt)` pairs.
- Produce a few OpenPGP messages signed and/or encrypted under a fixed key pair, including detached signatures with `stripTrailingSpaces=true` semantics.
- Produce an `auth/info` signed-modulus blob (or capture one from a real session).

These vectors live in `tools/vectors/` as JSON files (committed to the repo), and our Kotlin tests load them and assert equality. The Node script is not in the APK; it lives in `tools/vectors/` for traceability.

## Decision

**Establish a one-shot vector-capture workflow:**

1. `tools/vectors/capture.mjs` — Node script depending on `@protontech/crypto` (pinned version). Reads `tools/vectors/inputs.json` (test inputs), writes:
   - `tools/vectors/srp.json` — list of `(input, output)` pairs for SRP-6a.
   - `tools/vectors/bcrypt_sha512.json` — list of `(password, salt, expected_key_password)`.
   - `tools/vectors/openpgp.json` — sign-only, encrypt-only, encrypt-and-sign messages with the canonical fixture key pair.
   - `tools/vectors/canonical_text.json` — input strings + their `stripTrailingSpaces=true` canonical form.

2. The script's pinned `package.json` and `package-lock.json` are committed alongside; `node --version` is recorded as a comment in the JSON outputs for traceability.

3. Kotlin tests in `:core:crypto` and `:core:proton-contacts` load these JSON files and assert byte-exact match against our implementations.

4. **Vectors are regenerated only when:**
   - We bump the pinned `@protontech/crypto` version, or
   - We add a new test case (`inputs.json` change).
   Regeneration commits both `inputs.json` changes and the corresponding output JSON in the same change.

5. The capture script runs in CI as a "smoke" job (not on every PR — only on changes under `tools/vectors/`) to ensure the inputs/outputs stay in sync.

**Test cases (minimum):**

- SRP: 3 inputs covering normal + Unicode password + long password.
- bcrypt-SHA512: 3 inputs covering ASCII password + Unicode password + empty password.
- OpenPGP detached signature: 2 inputs covering ASCII vCard fragment + UTF-8 vCard fragment with non-ASCII (signature canonicalization stress).
- OpenPGP encrypt-and-sign: 2 inputs round-trip (encrypt → decrypt → verify match plaintext, verification status SIGNED_AND_VALID).
- Canonical text: 5 inputs covering trailing-whitespace, CRLF vs LF, mixed line endings, leading/trailing-empty lines, lines longer than 76 chars.

## Alternatives considered

- **Hand-write vectors from RFC 5054 + RFC 4880.** Rejected for SRP — RFC 5054's appendices give a few vectors but they don't cover Proton's specific choices. RFC 4880 gives some PGP vectors but not the canonicalization edge cases we need.
- **Test against a live Proton account in CI.** Rejected — cannot share credentials, account would be rate-limited or captcha-challenged.
- **Skip vectors; rely on integration test against a live account.** Rejected — single point of failure; first failure is hard to debug because the entire stack runs in the failing test.

## Consequences

- Every contributor capable of touching `:core:crypto` needs Node + the pinned `@protontech/crypto` package once, to regenerate vectors if `inputs.json` changes.
- The `tools/vectors/` directory is excluded from the APK and from `app:assembleRelease` Lint.
- A drift between Proton's spec and our impl is caught at PR time, not in production.
- If `@protontech/crypto` introduces breaking changes (algorithm bump, format change), the vector regeneration surfaces them immediately.
- We commit to maintaining the Node script as part of the project. The script is small (< 200 LOC) and won't drift much.

## Validation

- `./gradlew :core:crypto:test` loads every JSON file in `tools/vectors/` and runs assertions against it. Test count > 0 enforced.
- A canary test loads an unknown file from `tools/vectors/` and fails the build if any new vector files are added without test coverage.
- The Node script runs cleanly on a fresh `npm ci` from the repo root.
