<!--
  SPDX-License-Identifier: GPL-3.0-only
  SPDX-FileCopyrightText: 2026 pcontacts contributors
-->

# Proton SRP modulus signing key — pinned resource slot (ADR-0014)

## What goes here

A file named **`proton_srp_signing_key.asc`** in *this directory*
(`core/crypto/src/main/resources/`) containing Proton AG's SRP
modulus signing public key as an ASCII-armored OpenPGP public key
block:

```
-----BEGIN PGP PUBLIC KEY BLOCK-----
...
-----END PGP PUBLIC KEY BLOCK-----
```

When the file is present and parseable, `BouncyCastleProtonModulusVerifier`
verifies every `Modulus` value the Proton auth/info endpoint returns
against this key. A verification failure aborts the login (treated
as a MITM downgrade attempt).

## Why the file isn't here yet

The real key needs to be sourced from a verified Proton-controlled
channel (their published security documentation, their key
transparency log, or a known-good `@protontech/crypto` release).
Embedding a placeholder would either (a) cause every login to fail
verification, or (b) cause every login to "succeed" against a bogus
key, which is worse than no verification at all. So the resource
stays absent in source control, and the verifier's `NO_SIGNER_KEY`
branch fires — logging a loud warning that mod-sig verification
isn't running.

## How to add the key (release engineering)

1. Obtain the armored key from Proton's published channel.
2. Verify the key's fingerprint matches Proton's published one
   out-of-band (a different channel from how you obtained the key).
3. Save the ASCII-armored block at
   `core/crypto/src/main/resources/proton_srp_signing_key.asc`.
4. Update ADR-0014 with the fingerprint + provenance.
5. Update `BouncyCastleProtonModulusVerifierTest` to assert the
   resource loads and parses.
6. Once that lands, change the orchestrator's `NO_SIGNER_KEY`
   policy from "log warn + proceed" to "abort login" in a
   follow-up commit (the production-gate flip).

## What the verifier does today (without the file)

`BouncyCastleProtonModulusVerifier.loadPinnedKeyFromClasspath()`
returns null when the file is absent. The orchestrator logs a
warning at every login but proceeds — this matches the current
`[A]`-marked behaviour in `SrpLoginOrchestrator`. Once the file
is in place, the warning stops and signature verification becomes
mandatory.
