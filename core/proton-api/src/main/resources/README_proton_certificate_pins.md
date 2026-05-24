<!--
  SPDX-License-Identifier: GPL-3.0-only
  SPDX-FileCopyrightText: 2026 pcontacts contributors
-->

# Proton SPKI certificate pins — pinned resource (ADR-0012)

## What is here

**`proton_certificate_pins.txt`** — SHA-256 SPKI pins for the ISRG
trust anchors that sign `api.proton.me`'s TLS chain.

| Pin | Subject | Expiry |
|-----|---------|--------|
| `C5+lpZ7tc…` | ISRG Root X1 (RSA 4096) | 2035-06-04 |
| `diGVwiVYb…` | ISRG Root X2 (EC P-384) | 2040-09-17 |

## Pinning strategy

We pin the two ISRG **root** certificates rather than the leaf or
intermediate:

- **Leaf pins** break every ~90 days when Let's Encrypt rotates
  certificates.
- **Intermediate pins** break when Let's Encrypt cycles intermediate
  keys (R3 → R10 → R11 → R13 — this has happened several times).
- **Root pins** survive both rotations. The app only breaks if Proton
  migrates away from the ISRG chain entirely — a deliberate CA
  change, which is extremely rare and worth an app update.

## How the pins were captured

```bash
# ISRG Root X1
curl -s https://letsencrypt.org/certs/isrgrootx1.pem \
  | openssl x509 -pubkey -noout \
  | openssl pkey -pubin -outform DER \
  | openssl dgst -sha256 -binary | base64

# ISRG Root X2
curl -s https://letsencrypt.org/certs/isrg-root-x2.pem \
  | openssl x509 -pubkey -noout \
  | openssl pkey -pubin -outform DER \
  | openssl dgst -sha256 -binary | base64
```

Cross-verified against the live `api.proton.me` chain (2026-05-24):
leaf is `CN=proton.me`, signed by `CN=R13` (Let's Encrypt
intermediate), which chains to ISRG Root X1.

## Rotation

If Proton migrates to a non-ISRG CA, the app will refuse TLS
handshakes until updated with the new CA's pin. This is by design
(fail-closed). Ship both old + new pins in the transitional release.
