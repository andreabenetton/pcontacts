<!--
  SPDX-License-Identifier: GPL-3.0-only
  SPDX-FileCopyrightText: 2026 pcontacts contributors
-->

# Proton SPKI certificate pins — pinned resource slot

## What goes here

A file named **`proton_certificate_pins.txt`** in *this directory*
(`core/proton-api/src/main/resources/`) containing the SHA-256
SPKI pins of `api.proton.me`'s leaf certificate chain, one pin per
line. The expected line format matches OkHttp's
`CertificatePinner` syntax:

```
# Comments start with '#' and blank lines are ignored.
sha256/BASE64_SPKI_HASH_OF_LEAF=
sha256/BASE64_SPKI_HASH_OF_INTERMEDIATE=
```

Two pins are the recommended minimum (current leaf + backup) so
cert rotation doesn't brick installed clients.

## Why the file isn't here yet

The real pins need to be sourced from a verified
Proton-controlled channel — their published security
documentation, their key-transparency log, or a known-good cert
chain captured out-of-band. Embedding placeholder pins would
either (a) cause every HTTPS handshake to fail, or (b) cause
pinning to silently no-op (the empty-pin path documented below).
Either is worse than the explicit "no pins yet" state.

## How to compute a pin

```bash
openssl s_client -servername api.proton.me -connect api.proton.me:443 < /dev/null 2>/dev/null \
  | openssl x509 -pubkey -noout \
  | openssl rsa -pubin -outform der 2>/dev/null \
  | openssl dgst -sha256 -binary \
  | openssl base64
```

Verify the resulting hash against an out-of-band-trusted copy of
Proton's published pin set before committing.

## How to add the pins (release engineering)

1. Capture the leaf + intermediate SPKI pins from Proton.
2. Independently verify the hashes match Proton's published list.
3. Save them at
   `core/proton-api/src/main/resources/proton_certificate_pins.txt`.
4. Add a row to `docs/adr/0014-*.md` (or open a follow-up ADR)
   noting the pin source + rotation policy.
5. Update `ProtonCertificatePinsTest` to assert the resource loads.
6. Consider a release-build gate that fails CI when this resource
   is empty (defence against an accidental "ship without pinning"
   commit).

## What the pinner does today (without the file)

`ProtonCertificatePins.loadFromClasspath()` returns an empty list
when the file is absent. `ProtonCertificatePins.buildPinner()`
then constructs an OkHttp `CertificatePinner` with NO entries for
`api.proton.me` — effectively unpinned. The DNS guard
(`ProtonHostDnsGuard`) still refuses non-`*.proton.me` hosts, so a
compromise narrows to "Proton's cert chain trusted by Android's
system store". Pinning closes that gap; until the resource lands
the trust boundary is the system store.
