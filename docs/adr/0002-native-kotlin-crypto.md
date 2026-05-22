# ADR-0002: Crypto strategy — native Kotlin (BouncyCastle + ported SRP/bcrypt-SHA512)

- **Status:** Accepted
- **Date:** 2026-05-22
- **Deciders:** project owner
- **Related:** ADR-0013, ADR-0014

## Context

Proton's web client performs all client-side cryptography through `@protontech/crypto` (which in turn wraps `openpgpjs` and Proton's own SRP/bcrypt-SHA512 helpers). We need an Android equivalent for:

1. SRP-6a authentication (RFC 5054 + Proton's SRP version, with signed modulus).
2. `computeKeyPassword(password, salt) = bcrypt-SHA512` per Proton's published spec `[A]` — must be bit-exact to unlock user keys.
3. OpenPGP encrypt / decrypt / sign / verify, with detached signatures and canonical-text canonicalization (the web client passes `stripTrailingSpaces: true`).
4. Future encrypt-and-sign for write-back (phase 9).

There are three plausible architectures:

- **A. Native Kotlin port** — implement SRP/bcrypt-SHA512 in `:core:crypto`, use BouncyCastle for OpenPGP.
- **B. Embed JS engine** — bundle `openpgpjs` + `@protontech/crypto` and run it via QuickJS / J2V8 / `WebView` headless context.
- **C. Hybrid** — BouncyCastle for OpenPGP (hot path); JS engine only for the one-off SRP login.

Costs we evaluated:

- APK size: native ≈ +1 MB (BouncyCastle); JS engine ≈ +5–15 MB.
- F-Droid compatibility (ADR-0003): JS-engine bundles trigger anti-feature flags for embedded interpreters and complicate reproducible builds. Native is clean.
- Audit surface: native ports are auditable line-by-line. JS embedding pulls in a much larger TCB (the JS engine, its sandbox, the JS library tree).
- Interop risk: native carries higher porting risk (off-by-one canonicalization, wrong bcrypt cost factor) but bounded — see ADR-0013 for the mitigation.

## Decision

Adopt **option A**: native Kotlin crypto. `:core:crypto` exposes a small typed surface (`ProtonCryptoService`, `SrpClient`, `KeyPasswordDerivation`) implemented with:

- **BouncyCastle** `org.bouncycastle:bcpg-jdk18on`, `bcprov-jdk18on` (>= 1.78) for OpenPGP.
- **Ported SRP-6a** in Kotlin against RFC 5054 + Proton's SRP version field (`InfoResponse.Version`).
- **Ported bcrypt-SHA512** as `bcrypt(SHA-512(password))` with cost parameter from Proton's spec — neither `jbcrypt` nor `bcrypt-jvm` does the SHA-512 pre-hash on its own.
- **Modulus pinning** — see ADR-0014.

No JS engine is bundled. The app never executes JavaScript.

## Alternatives considered

- **Embed openpgp.js + pmcrypto via QuickJS / J2V8.** Rejected: APK bloat, fragile across ABIs, fails F-Droid's preference for non-interpreter builds.
- **Hybrid (JS only for SRP).** Rejected: pulls in the entire JS engine surface for a single one-off operation, defeats the simplification.
- **Use Proton's official Android `proton-libs` crypto.** Considered — `pmcrypto-android` exists in Proton's GitHub but its release artifacts are not on Maven Central, change without notice, and lack a stable Kotlin API. Re-evaluate if Proton publishes a Maven-Central artifact under GPL-3.0 with a documented contract.

## Consequences

- We own ~600–1200 LOC of crypto code (SRP + bcrypt-SHA512 + thin BouncyCastle adapters). Every change in this area requires the test-vector regression suite (ADR-0013) to pass.
- APK stays small and F-Droid-clean.
- Bit-exact interop with `@protontech/crypto` is now our problem. ADR-0013 captures vectors. ADR-0014 captures modulus signature verification.
- Future write-back (phase 9) can reuse the same crypto module — encrypt+sign uses the same BouncyCastle primitives.
- If Proton changes their SRP version, bcrypt parameters, or canonicalization rules, we ship a crypto update; users on old APKs cannot log in until they upgrade.

## Validation

- Captured-vector unit tests (ADR-0013) pass for SRP, bcrypt-SHA512, OpenPGP encrypt-then-decrypt, sign-then-verify.
- An end-to-end login against a real test Proton account succeeds and returns a valid `ServerProof` match.
- An end-to-end decrypt against a real test account decrypts at least one full contact with verification status `SIGNED_AND_VALID`.
