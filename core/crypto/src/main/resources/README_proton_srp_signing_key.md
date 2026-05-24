<!--
  SPDX-License-Identifier: GPL-3.0-only
  SPDX-FileCopyrightText: 2026 pcontacts contributors
-->

# Proton SRP modulus signing key — pinned resource (ADR-0014)

## What is here

**`proton_srp_signing_key.asc`** — Proton AG's SRP modulus signing
public key, ASCII-armored Ed25519 (EdDSA).

- **UID:** `proton@srp.modulus`
- **Key ID:** `3505 85C4 E951 8F26`
- **Algorithm:** Ed25519 (EdDSA sign) + Curve25519 (ECDH encrypt)

## Provenance

Sourced from two independent Proton-controlled repositories that
ship the identical key:

1. [ProtonMail/go-srp](https://github.com/ProtonMail/go-srp) —
   `modulusPubkey` constant in `srp.go` (Go SRP library, MIT).
2. [emersion/hydroxide](https://github.com/emersion/hydroxide) —
   `protonmail/srp.go` (third-party bridge that has been in
   production use against real Proton accounts since 2018).

Both sources return byte-identical armored blocks.

## What the verifier does

`BouncyCastleProtonModulusVerifier.loadPinnedKeyFromClasspath()`
reads this file. On every `POST core/v4/auth/info` response, the
verifier checks the OpenPGP detached signature on the `Modulus`
field against this key:

- **VALID** — signature checks out; SRP proceeds.
- **INVALID** — signature fails; login aborts (MITM assumed).
- **NO_SIGNER_KEY** — file absent or unparseable; login aborts
  (production policy, per ADR-0014).

## Rotation

If Proton rotates their SRP signing key, this file must be updated
and the app re-released. The verifier can hold two keys (current +
next) when a rotation is announced — add a second key ring to the
file or ship a second `.asc` resource.
