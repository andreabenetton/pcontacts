# ADR-0014: SRP modulus pinning — verify each `auth/info` modulus against Proton's signing key

- **Status:** Accepted
- **Date:** 2026-05-22
- **Deciders:** project owner
- **Related:** ADR-0002, ADR-0012

## Context

The SRP-6a authentication flow starts with `POST core/v4/auth/info`, which returns `{Modulus, ServerEphemeral, Version, Salt, SRPSession}`. The `Modulus` is the large prime `N` used in the SRP arithmetic.

If an attacker can substitute a known-weak modulus (a smooth-prime-factor `N`, a non-prime, or a value for which they know the discrete log of `g`), they can recover the user's hashed password from the SRP exchange — defeating the whole protocol.

Proton mitigates this by returning the modulus as an **OpenPGP-signed blob**, signed by a long-lived Proton-controlled SRP signing key. The web client verifies this signature before running SRP. We must do the same; otherwise:

- A network attacker (BGP hijack, malicious WiFi, compromised Proton infrastructure) can substitute a weak modulus and recover hashed passwords on the fly.
- TLS certificate pinning (ADR-0012) does not help — a Proton-side compromise (or a compromised TLS terminator) would deliver a tampered modulus over a valid TLS channel.

The web client gets the Proton SRP public key from a bundled constant (it ships in `@protontech/crypto`). We need the same public key, baked into our build, never fetched from the network.

The `Modulus` field in the response is a clearsigned OpenPGP message: the cleartext is a base64-encoded SRP modulus, with a detached or inline PGP signature.

## Decision

**Pin Proton's SRP modulus signing public key in the app build.**

- Asset path: `core:crypto/src/main/resources/proton_srp_signing_key.asc`. ASCII-armored OpenPGP public key, exactly as published by Proton in their crypto package.
- Fingerprint pinned in `BuildConfig.PROTON_SRP_KEY_FINGERPRINT` (compile-time constant). The runtime verifier asserts the loaded key's fingerprint matches before using it.
- On every `auth/info` response: verify the returned `Modulus` blob using BouncyCastle's PGP signature verifier against the pinned key. **On verification failure: abort the login flow with a security error**, never proceed to SRP arithmetic.

**Key sourcing:**

- Initially copied from `@protontech/crypto`'s published assets at the pinned commit recorded in `docs/API_RESEARCH.md` (when that doc lands).
- The asset and fingerprint are reviewed line-by-line in the PR that introduces them — this is the most security-critical bytes in the repo.

**Key rotation:**

- If Proton rotates their SRP signing key, current installations fail to log in until they update.
- We ship up to **two** pinned keys (current + next) when Proton announces rotation. The verifier accepts a signature from either.
- A future ADR will cover "remote-update" of pinned keys via a signed, auditable update channel — but **not** in MVP. For MVP, the app update is the only update path.

**Independent of TLS certificate pinning (ADR-0012):** these are layered defenses against different attackers. Certificate pinning protects the transport. Modulus pinning protects the SRP protocol regardless of transport compromise.

## Alternatives considered

- **Trust the modulus from `auth/info` without verification.** Rejected — defeats SRP under any in-path or server-side attacker.
- **Fetch the pinned key from a Proton URL at first run.** Rejected — same in-path attacker can substitute the key.
- **Pin only the modulus value, not the signing key.** Rejected — Proton legitimately rotates the modulus; pinning the value blocks legitimate operation.
- **Verify the modulus by checking it's a safe prime.** Considered as an additional defense, not a replacement. A safe-prime check is cheap and orthogonal to signature verification — we may add it later as a belt-and-suspenders measure (it doesn't prevent the "attacker knows `g`'s discrete log" attack, but it forecloses naïve weak-prime substitution).

## Consequences

- We own one ASCII-armored public key in the repo and one BuildConfig fingerprint constant. Both are highly sensitive bytes — any change requires a security-labeled PR review.
- A Proton-side key rotation requires an app update before users can log in.
- Login flow has one additional fail-closed branch: if signature verification fails, present a security-error screen and disable login until the user has explicitly acknowledged a "potentially insecure modulus" warning (we may not even allow override — we lean toward fail-closed).
- The build's reproducibility is unaffected; the key is static bytes.

## Validation

- Unit test in `:core:crypto`: feed a known-good signed-modulus fixture and the pinned key → verification returns SIGNED_AND_VALID and the parsed modulus matches the expected value.
- Negative test: feed a signed-modulus fixture signed by a different key → verification fails, login aborts with a security error.
- Negative test: tamper one byte of the modulus content → signature verification fails.
- Code review checklist: any change to `proton_srp_signing_key.asc` or `BuildConfig.PROTON_SRP_KEY_FINGERPRINT` requires two reviewers and a link to the upstream Proton source for the new key.

## Implementation status

Verifier machinery is shipped and tested; the pinned key resource is not. Concretely:

- `ProtonModulusEnvelope.decode(serverValue)` in `:core:crypto/srp/` peels the OpenPGP cleartext envelope (raw base64 passthrough for unenveloped fallbacks). 6 tests.
- `BouncyCastleProtonModulusVerifier` reads the armored key from `/proton_srp_signing_key.asc` on the classpath at construction, verifies detached signatures via `OpenPgpService.verifyDetached`, returns one of `VALID` / `INVALID` / `NO_SIGNER_KEY`. 6 tests including tamper + attacker-key paths.
- `SrpLoginOrchestrator` calls the verifier on every `auth/info` response. On `VALID` → proceed. On `INVALID` → `LoginResult.Failed(reason = "modulus_signature_invalid")` (aborts login as this ADR's Decision requires). On `NO_SIGNER_KEY` → log warn + proceed.
- The pinned-key resource at `core/crypto/src/main/resources/proton_srp_signing_key.asc` is intentionally absent in source control. A README at the same path documents the source-and-pin procedure + the production-gate flip (change the `NO_SIGNER_KEY` branch from warn-and-proceed to abort once the key lands).

Production-gating policy mismatch: this ADR's Decision says "abort the login flow with a security error" on verification failure, which the code does for `INVALID`. For `NO_SIGNER_KEY` (no pinned key configured), today's code warns and proceeds — a tactical deviation while the pinned key is being sourced. The next commit that drops the key in **must** flip that branch to abort; tracked as part of [`docs/THREAT_MODEL.md §5.1`](../THREAT_MODEL.md).

`BuildConfig.PROTON_SRP_KEY_FINGERPRINT` is not wired yet — the current verifier accepts the resource's first PGPPublicKeyRing without a separate fingerprint cross-check. Add the constant in the same commit that lands the resource.
