<!--
  SPDX-License-Identifier: GPL-3.0-only
  SPDX-FileCopyrightText: 2026 pcontacts contributors
-->

# Proton API research

Date: 2026-05-24. Last validated against the live Proton production
API on the same date.

Primary sources:

- `ProtonMail/go-srp` — the Go SRP library that the Proton server
  and official clients use.
- `ProtonMail/WebClients` (GPL-3.0) — the Proton web client, used
  as a specification source for endpoint paths, DTO shapes, and
  crypto flows.
- Live integration testing against `mail-api.proton.me` (production).

Verification markers: `[V]` verified (source + live test), `[U]`
unverified (present in code but not fully knowable from source
alone), `[A]` assumption (must be validated), `[D]` discouraged or
out of scope.

---

## 1. Base URL and connectivity

| Finding | Status | Detail |
|---|---|---|
| Base URL | `[V]` | `https://mail-api.proton.me/`. The hostname `api.proton.me` does **not** resolve via public DNS as of 2026-05-24. |
| TLS | `[V]` | Standard HTTPS. SPKI pins are wired in code (`ProtonCertificatePins`) but the pin set needs periodic refresh from Proton's published infrastructure. |
| DNS guard | `[V]` | `ProtonHostDnsGuard` rejects any host not matching `*.proton.me`. |

---

## 2. Request headers

Every request to the Proton API must carry these headers `[V]`:

| Header | Value | When |
|---|---|---|
| `accept` | `application/vnd.protonmail.v1+json` | Always |
| `x-pm-appversion` | `android-mail@<semver>` | Always |
| `x-pm-uid` | `<UID from auth response>` | After login |
| `Authorization` | `Bearer <AccessToken>` | After login |
| `x-pm-locale` | `<e.g. en_US>` | Optional |

### `x-pm-appversion` validation

The Proton server validates the `x-pm-appversion` header against a
known set of client identifiers and a sliding version window `[V]`.

The appversion is a client identifier that selects a server-side API
**contract** — it is not "the latest official app version". The value
we send must match the direct `auth/info` SRP flow this app implements.

`[V]` Verified live against `POST core/v4/auth/info` on 2026-07-28:

| `android-mail@<semver>` | Result |
|---|---|
| `1.0.0` | 422 `Code 5003` (force upgrade — too old) |
| `2.0.0` … `3.0.12` | 200 `Code 1000` + `Modulus` ✅ |
| `3.0.13` and up (incl. the 7.x line, `99.0.0`) | 401 "Invalid access token" |

`[U]` 3.0.13+ appear to require an **unauthenticated-session** token
obtained before `auth/info` (Proton's newer apps establish a session
first); this app implements only the older direct flow, so it must
stay in `2.0.0`–`3.0.12`. Bumping to the latest android-mail release
breaks login (see the v1.3.0 regression).

Current value: `android-mail@3.0.12` (set in `ProtonApiConfig`).

### Version-rejection detection

When the pinned version ages out of Proton's acceptance window, the
server responds with a JSON body containing a specific error code
rather than a generic 4xx:

| Code | Meaning | Status |
|---|---|---|
| 5003 | Force upgrade (bad app version) | `[V]` |
| 5004 | API version not supported | `[A]` |

The HTTP status code varies (401, 422, or 400 depending on how far
out of window the version is), so detection keys on the JSON `Code`
field, not the HTTP status `[V]`.

`AppVersionRejectionInterceptor` peeks the response body for these
codes and throws `AppVersionRejectedException` (an `IOException`
subclass), letting callers distinguish "app needs update" from
transient IO errors or auth failures. The SyncAdapter maps this
exception to `numAuthExceptions` so the sync framework stops
retrying until the app is updated.

---

## 3. SRP authentication flow

Proton uses a **custom SRP variant** that diverges significantly
from RFC 5054 / standard SRP-6a. The definitive source is
`ProtonMail/go-srp`; the `@protontech/crypto` npm package wraps
go-srp compiled to WASM.

### 3.1 High-level flow

```
1. POST core/v4/auth/info  {Username, Intent:"Proton"}     [V]
   → {Modulus, ServerEphemeral, Version, Salt, SRPSession}

2. Verify Modulus OpenPGP signature (ADR-0014)              [V]

3. Decode Modulus + ServerEphemeral from base64              [V]
   (both arrive as little-endian byte arrays)

4. Derive x = fromLE(hashPassword(password, Salt, Modulus))  [V]

5. SRP client computation → {A, M1, M2_expected}            [V]

6. POST core/v4/auth  {Username, Payload:{},                 [V]
     ClientEphemeral=base64(A_LE),
     ClientProof=base64(M1),
     SRPSession}
   → {AccessToken, RefreshToken, UID, ServerProof, TwoFactor}

7. Verify ServerProof matches M2_expected                    [V]

8. If TwoFactor bit 0 set:                                   [V]
     POST core/v4/auth/2fa  {TwoFactorCode}

9. GET core/v4/users → User.Keys[]                           [V]
10. GET core/v4/keys/salts → KeySalts[]                      [V]
11. keyPassword = computeKeyPassword(password, KeySalt)       [V]
12. Persist {UID, AccessToken, RefreshToken, keyPassword}
```

### 3.2 Byte encoding convention

**All BigInteger values on the wire use little-endian byte order**
`[V]`. This is go-srp's `fromNat`/`toNat` convention:

- Modulus (`N`): API sends base64(N_LE). Decode, then reverse to
  get big-endian for `BigInteger(1, reversed)`.
- ServerEphemeral (`B`): same encoding.
- ClientEphemeral (`A`): encode as `base64(A_LE)` = big-endian
  padded, then reversed.
- ClientProof (`M1`): 256 raw bytes, sent as base64 directly (no
  additional reversal — it's a hash output, not an integer).
- ServerProof (`M2`): same as M1.

Pad length for all integer-to-bytes conversions:
`padLen = ⌈bitLength(N) / 8⌉` (256 bytes for the 2048-bit Proton
modulus).

### 3.3 expandHash — the universal hash function

Proton SRP replaces all standard SRP hash operations (typically
SHA-256 or SHA-1) with `expandHash`, which produces **256 bytes**:

```
expandHash(input) =
    SHA-512(input ‖ 0x00)
  ‖ SHA-512(input ‖ 0x01)
  ‖ SHA-512(input ‖ 0x02)
  ‖ SHA-512(input ‖ 0x03)
```

Four SHA-512 digests concatenated, each with a single-byte counter
appended to the input. Source: go-srp `expandHash` function.

### 3.4 SRP formulas (how Proton diverges from RFC 5054)

| Symbol | RFC 5054 | Proton (go-srp) |
|---|---|---|
| **k** | `H(N ‖ g)` | `fromLE(expandHash(g_LE ‖ N_LE)) mod N` — **g first**, not N first |
| **u** | `H(A ‖ B)` | `fromLE(expandHash(A_LE ‖ B_LE))` |
| **A** | `g^a mod N` | Same |
| **S** | `(B - k·g^x)^(a + u·x) mod N` | Same arithmetic |
| **M1** | `H(H(N)⊕H(g) ‖ H(I) ‖ s ‖ A ‖ B ‖ K)` | `expandHash(A_LE ‖ B_LE ‖ S_LE)` — **no identity, no salt, no N/g terms** |
| **M2** | `H(A ‖ M1 ‖ K)` | `expandHash(A_LE ‖ M1 ‖ S_LE)` — uses **raw S**, not K |
| **K** | `H(S)` | **Not computed.** Session key = raw S as LE bytes |
| **Hash** | SHA-1 or SHA-256 | expandHash (4×SHA-512 = 256 bytes) |
| **Byte order** | Big-endian | **Little-endian** throughout |

Key differences summarised:

1. **Little-endian everywhere.** Every BigInteger↔byte conversion
   reverses relative to standard SRP.
2. **expandHash replaces H().** All hash computations produce 256
   bytes via 4×SHA-512 with counter bytes, not a single digest.
3. **M1 is drastically simplified.** No `H(N)⊕H(g)`, no `H(I)`
   (username hash), no salt. Just `expandHash(A ‖ B ‖ S)`.
4. **M2 uses raw S, not K.** Standard SRP defines K = H(S) as the
   session key and uses K in M2. Proton uses S directly.
5. **k has reversed operand order.** `expandHash(g ‖ N)` not
   `expandHash(N ‖ g)`.

### 3.5 hashPassword — SRP x derivation

The SRP `x` parameter is derived from the user's password via
`hashPassword` (version 4 / `hashPasswordVersion3` in go-srp) `[V]`:

```
1. rawSalt = base64Decode(authInfo.Salt)
2. saltWithSuffix = rawSalt ‖ ASCII("proton")
3. bcryptSalt = first 16 bytes of saltWithSuffix
4. unexpandedHash = bcrypt(password, "$2y$10$" + bcryptEncode(bcryptSalt))
   → full 60-character bcrypt output string
5. hashBytes = charCodeAt(unexpandedHash)  // each char → its byte value
6. concat = hashBytes ‖ modulusBytes       // modulus in LE wire format
7. result = expandHash(concat)             // → 256 bytes
8. x = fromLE(result)                      // interpret as LE BigInteger
```

**Critical detail on salt handling**: The salt from `auth/info` is a
base64 string. go-srp **base64-decodes** it to raw bytes, then
appends the ASCII bytes of `"proton"`, then takes the first 16 bytes.
It does NOT use the ASCII bytes of the base64 string itself. This
was confirmed by live testing — using the string's ASCII bytes
produces an `auth_failed` rejection; using decoded bytes succeeds.

The bcrypt cost is **10** (`$2y$10$`). BouncyCastle's
`OpenBSDBCrypt.generate()` handles the bcrypt-specific base64
encoding of the 16-byte salt internally.

### 3.6 Modulus signature verification (ADR-0014)

The `auth/info` response returns the modulus wrapped in an OpenPGP
cleartext-signed envelope `[V]`. The structure:

```
-----BEGIN PGP SIGNED MESSAGE-----
Hash: SHA256

<base64-encoded modulus bytes (LE)>
-----BEGIN PGP SIGNATURE-----
<detached signature>
-----END PGP SIGNATURE-----
```

The pinned Proton SRP signing public key is shipped as
`proton_srp_signing_key.asc` in `:core:crypto` resources.
`BouncyCastleProtonModulusVerifier` verifies the cleartext against
this key. On `INVALID` → login aborts. On `NO_SIGNER_KEY` → login
aborts (fail-closed after ADR-0014 gate flip).

### 3.7 ChallengePayload

The `POST core/v4/auth` endpoint accepts a `Payload` field `[V]`.
In the web client this is computed by `@protontech/challenge` (an
anti-bot package). **An empty map `{}` is accepted by the server**
— validated by live testing on 2026-05-24.

This may change. If Proton tightens enforcement, login will fail
with an HTTP error on the `auth` call (surfaced as `auth_failed`).
See risk #2 in the plan risk register.

---

## 4. Key-password derivation (computeKeyPassword)

After login, the app derives `keyPassword` to unlock the user's PGP
private key `[V]`:

```
1. GET core/v4/users         → User.Keys[] (armored private key, key ID)
2. GET core/v4/keys/salts    → KeySalts[] (keySalt per key ID)
3. Find the primary active key (primary==1, active==1)
4. Find its keySalt in the salts response
5. keySalt bytes = base64Decode(keySalt)  // exactly 16 bytes
6. keyPassword = bcrypt(password, "$2y$10$" + bcryptEncode(keySaltBytes))
7. Strip first 29 chars ("$2y$10$" + 22-char encoded salt)
8. Result = trailing 31-character hash string
```

This is a **different bcrypt operation** from `hashPassword`:

| | hashPassword (SRP x) | computeKeyPassword |
|---|---|---|
| Salt source | `auth/info` Salt field | `keys/salts` KeySalt field |
| Salt pre-processing | decode + append "proton" + take 16 | decode (already 16 bytes) |
| Post-processing | charCodeBytes → concat with modulus → expandHash → 256 bytes | strip `$2y$10$` prefix → 31-char trailing hash |
| Purpose | SRP proof generation | PGP private key unlock |

---

## 5. Token refresh

`[V]` Behaviour confirmed from WebClients source:

- On 401: single-flight `POST auth/refresh {RefreshToken}` under a
  mutex. Update tokens. Replay original request once.
- On 429: Fibonacci backoff (1s, 2s, 3s, 5s, 8s) honouring
  `Retry-After`. Cap at 5 retries.
- On 9001 (human verification): surface as typed exception. Never
  auto-retry.

---

## 6. Contacts endpoints

These endpoint shapes are verified from WebClients source `[V]`:

| Method | Path | Auth | Notes |
|---|---|---|---|
| GET | `contacts/v4/contacts` | Yes | Metadata list, paginated |
| GET | `contacts/v4/contacts/{id}` | Yes | Full contact with `Cards[]` |
| GET | `contacts/v4/contacts/emails` | Yes | Email list, paginated |
| POST | `contacts/v4/contacts` | Yes | Create/import |
| PUT | `contacts/v4/contacts/{id}` | Yes | Update (replaces entire Cards[]) |
| PUT | `contacts/v4/contacts/delete` | Yes | Bulk delete |

**Never used:** `GET contacts/v4/contacts/export` — this is the
server-side decrypt path, forbidden by ADR-0007.

---

## 7. Validation status

Validated end-to-end against the live Proton production API on
2026-05-24:

| Step | Result | Marker flip |
|---|---|---|
| `POST core/v4/auth/info` | 200, valid DTO | `[A]` → `[V]` |
| Modulus OpenPGP envelope parse | Signature valid against pinned key | `[U]` → `[V]` |
| hashPassword salt derivation | bcrypt output accepted by server | `[A]` → `[V]` |
| SRP proof (M1) | Server accepted ClientProof | `[A]` → `[V]` |
| ServerProof (M2) verification | M2 matched our expectation | `[A]` → `[V]` |
| Token persistence | AccessToken, RefreshToken, UID stored | `[V]` |
| `GET core/v4/users` | 200, User.Keys[] returned | `[V]` |
| `GET core/v4/keys/salts` | 200, KeySalts[] returned | `[V]` |
| computeKeyPassword derivation | keyPassword derived and stored | `[A]` → `[V]` |
| Logout (`DELETE core/v4/auth`) | 200, session revoked | `[V]` |
| ChallengePayload (empty map) | Accepted | `[U]` → `[V]` |

### Remaining `[U]` markers

- ChallengePayload long-term acceptance. Empty `{}` worked on
  2026-05-24; Proton may enforce non-empty payloads in the future.
- FIDO2 / WebAuthn 2FA envelope shape (deferred, TOTP-only for MVP).
- `x-pm-appversion` window drift — our hardcoded version may fall
  out of the acceptance window as Proton ships updates.

### Remaining `[A]` markers

All previously listed `[A]` markers have been validated (2026-05-24):

- Contact Card decrypt + merge end-to-end: `[A]` → `[V]`.
  LiveProtonLoginTest decrypts ENCRYPTED_AND_SIGNED (type 3) and
  verifies SIGNED (type 2) cards from a real account. vCard merge
  produces correct fullName, emails, phones. Signature verification
  passes on 100% of cards.
- PGP private key unlock with the derived keyPassword: `[A]` → `[V]`.
  `BouncyCastleKeyUnlock.unlock(armored, keyPassword)` successfully
  unlocks primary + encryption subkey.  Contacts encrypted to the
  subkey decrypt correctly.
- Response envelope shapes for contacts endpoints: `[A]` → `[V]`.
  `{Code, ContactEmails, Total}` and `{Code, Contact}` both match.
- Canonicalization rules for signature verification: `[A]` → `[V]`.
  `stripTrailingSpaces=true` + canonical text mode produces valid
  signatures that Proton's server-signed cards also verify against.

---

## 8. Captured test vectors

`tools/vectors/capture.js` generates test vectors for:

- `computeKeyPassword` — 3 vectors (ASCII short, ASCII long,
  UTF-8 with Unicode characters).
- `srpHashPassword` — 3 vectors (same password/salt combinations,
  with a 256-byte test modulus).

Vectors are stored at
`core/crypto/src/test/resources/proton-crypto-vectors.json` and
consumed by `CapturedVectorsTest`. The vector generation script
matches the go-srp algorithm (base64-decode salt, append "proton",
bcrypt, expand).

---

## 9. Known risks and mitigations

See the plan's risk register (§16) for the full list. API-specific
highlights:

1. **Endpoint versioning.** Proton can drop `core/v4` at any time.
   Mitigation: tolerant DTO parsing (ignore unknown fields), CI
   that runs the live test periodically.
2. **ChallengePayload enforcement.** Currently accepted empty.
   Mitigation: if rejected, investigate `@protontech/challenge`
   source; last resort is WebView-based auth (out of scope for v1).
3. **appVersion window drift.** Our `3.0.12` sits at the top of the
   `2.0.0`–`3.0.12` window and may eventually age out.
   Mitigation: `AppVersionRejectionInterceptor` detects Code
   5003/5004 `[V]`/`[A]` and throws a typed exception so the
   SyncAdapter stops retrying and the user sees "app update
   required" instead of a silent auth loop. Still requires a
   code update to bump `ProtonApiConfig.DEFAULT_APP_VERSION`.
4. **Rate limiting / captcha.** Fibonacci backoff + 12h sync
   interval + 9001 typed exception.
