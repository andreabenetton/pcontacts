<!--
  SPDX-License-Identifier: GPL-3.0-only
  SPDX-FileCopyrightText: 2026 pcontacts contributors
-->

# pcontacts threat model

Date: 2026-05-24 (amended for ADR-0017/0018). Owners: project owner (single-maintainer at this
stage). This document is the STRIDE pass plan §15 / §17 task 20
calls for. It is **deliberately conservative**: when in doubt we
assume the threat exists, document the mitigation we have today,
and call out the residual risk explicitly.

If you find a security issue, please open a private GitHub issue
or contact the maintainer directly — do NOT file it as a public
PR.

---

## 1. Scope + assumptions

### In scope

- The pcontacts Android app (`io.pcontacts.app`), all `:core:*` and
  `:feature:*` library modules, build scripts, and the GPL-3.0
  source tree.
- The data path: Proton REST API ↔ this app ↔ Android
  `ContactsContract` provider ↔ system Contacts UI.
- All secrets the app holds: Proton session UID, AccessToken,
  RefreshToken, the bcrypt-SHA-512 mailbox `keyPassword`, the
  unlocked PGP user key (in-memory, sync-run-scoped), decrypted
  vCard plaintext (in-memory, sync-run-scoped).

### Out of scope

- Anything that doesn't run on the user's device with this APK
  installed. Proton's own infrastructure, the Android OS, the
  user's choice of lockscreen / disk encryption — we assume they
  do their job.
- The user's Proton password itself. We never persist it; we
  derive `keyPassword` from it during the SRP login flow and let
  the password go out of scope. Our threat model starts AFTER
  that derivation.
- Browser-based attackers on Proton's web client. Out of scope —
  that's Proton's threat model.
- Physical attackers with the unlocked device in hand and the
  app already in the foreground. ADR-0009 limits exposure but
  doesn't claim to defend against this.

### Assumptions

| # | Assumption | Justification |
|---|---|---|
| A1 | Android Keystore is honest (AES-GCM KEK at alias `pcontacts.kekv1` cannot be exfiltrated by another app, only by code running with our UID). | Standard Android security model. StrongBox-backed where the device supports it. |
| A2 | `EncryptedSharedPreferences` (androidx.security:security-crypto) is honest — its AES256_SIV + AES256_GCM cipher pair has no known break. | Reviewed crypto, Tink-backed. |
| A3 | The Android `ContactsContract` provider is honest — `caller_is_syncadapter=true` semantics work as documented (no duplicate-resurrection bug). | AOSP-documented, exercised by every account-syncing app. |
| A4 | The user's Proton account password is sufficiently strong to resist offline brute-force against the bcrypt-SHA-512 key-password (≥ 60 bits of entropy in practice). | Proton enforces a minimum complexity at signup. |
| A5 | The OkHttp + BouncyCastle releases pinned in `gradle/libs.versions.toml` do not contain a known CVE we're vulnerable to. | Dep-bump cadence per ADR-0015. Dependabot is enabled for Gradle + GitHub Actions ecosystems (`.github/dependabot.yml`). |

---

## 2. Asset inventory + sensitivity

| Asset | Lifetime | At rest | In transit | If leaked |
|---|---|---|---|---|
| Proton session **UID** | indefinite (until logout) | `EncryptedSharedPreferences` | `x-pm-uid` header on every request | Account fingerprinting; cannot read mail/contacts alone. |
| **AccessToken** | ~24h (Proton's `ExpiresIn`) | `EncryptedSharedPreferences` | `Authorization: Bearer …` header | Full read+write access to Proton REST API as the user until expiry. |
| **RefreshToken** | until revoked | `EncryptedSharedPreferences` | request body to `/auth/refresh` only | Long-lived foothold; equivalent to password-less re-login indefinitely. |
| **keyPassword** (bcrypt-SHA-512 string) | indefinite (until logout) | wrapped under Keystore AEAD KEK in EncryptedSharedPreferences | never on the wire | Offline decrypt of every Proton-encrypted Card on the device. |
| Unlocked **PGP user private key** | sync-run lifetime (seconds); re-unlocked for outbox push retries (ADR-0017/0018) | NEVER persisted; constructed from armored block + keyPassword on demand | never on the wire | As above. |
| Decrypted **vCard plaintext** | sync-run lifetime (seconds, per-contact) | NEVER persisted; lives only on the heap during ContactDecryptBootstrap → VCardMerger | never on the wire | Discloses contact list, emails, phones, addresses, notes. |
| Local **Room mapping** (`contact_map`, `group_map`, `sync_state`) | until logout / data wipe | plaintext SQLite (no decrypted content stored) | never on the wire | Discloses contact IDs + sync timestamps; no plaintext content. |
| **Outbox** (`outbox` table in Room) | until push succeeds or is discarded | plaintext SQLite; stores `op_type`, `payload_hash`, attempt metadata — no decrypted content. If three-way merge stores last-known server payloads, those are encrypted under the Keystore AEAD KEK before writing (ADR-0018). | never on the wire | Without payload: contact IDs + operation types (low sensitivity). With encrypted payload: protected at the same level as `keyPassword`. |
| Local **ContactsContract** rows under our account | until logout | plaintext (Android provider does its own at-rest encryption per filesystem class) | shared with other apps via READ_CONTACTS permission | Full contact disclosure to any app the user has granted READ_CONTACTS. |

---

## 3. Trust boundaries

```
┌──────────────────────────────────────────────────────────────────┐
│ User's device (Android, sandboxed UID)                           │
│                                                                  │
│ ┌─────────────────────────┐         ┌──────────────────────────┐ │
│ │ pcontacts process       │         │ ContactsContract         │ │
│ │  ┌────────────────────┐ │         │ provider (system)        │ │
│ │  │ Keystore alias     │ │         │                          │ │
│ │  │ pcontacts.kekv1    │ │         │ shared with: every app   │ │
│ │  └────────────────────┘ │         │ holding READ_CONTACTS    │ │
│ │  ┌────────────────────┐ │         │                          │ │
│ │  │ EncryptedSharedPref│─┼────────►│ wrote: RawContacts +     │ │
│ │  │ — secret blobs     │ │         │ Data rows under          │ │
│ │  └────────────────────┘ │         │ io.pcontacts.account     │ │
│ │  ┌────────────────────┐ │         └──────────────────────────┘ │
│ │  │ Room DB (mapping)  │ │                                      │
│ │  └────────────────────┘ │                                      │
│ │  ┌────────────────────┐ │                                      │
│ │  │ Heap: unlocked PGP │ │                                      │
│ │  │ keys + plaintext   │ │                                      │
│ │  │ vCards (transient) │ │                                      │
│ │  └────────────────────┘ │                                      │
│ └─────────┬───────────────┘                                      │
└───────────┼──────────────────────────────────────────────────────┘
            │ HTTPS, *.proton.me only (DNS guard), SPKI pin
            ▼
       ┌─────────────────────┐
       │ Proton REST API     │
       │ mail-api.proton.me  │
       └─────────────────────┘
```

The three crossings:

1. **App ↔ Proton API** — every HTTPS request. Guarded by
   `ProtonHostDnsGuard` (rejects non-`*.proton.me`),
   `CertificatePinner` (ISRG Root X1 + X2 SPKI pins, release-gated),
   `HeadersInterceptor` +
   `AuthInterceptor` (header sanitisation),
   `RefreshingAuthenticator` (401 → /auth/refresh under a
   single-flight mutex), `FibonacciBackoffInterceptor` (429
   tolerance), `HumanVerificationInterceptor` (9001 surfaces as
   a typed exception, never silently retried).
2. **App heap ↔ EncryptedSharedPreferences** — every secret
   read/write. Single-surface SecretStore interface; direct
   `SharedPreferences` constructor calls are forbidden outside
   `:core:storage` (CLAUDE.md anti-pattern, custom Android Lint
   rule enforces it for `Log.*` / `println` / `System.out.*`).
3. **App heap ↔ ContactsContract** — every contact write. Every
   URI passes through `SyncAdapterUri.decorate` so `caller_is_syncadapter=true`
   is set; `BatchApplier` is the only legitimate caller of
   `provider.applyBatch`. Delete-and-reinsert child Data rows
   (ADR-0010) keeps user-owned aggregate state (`starred`,
   `ringtone`, custom `Photo` stream) intact across updates.

---

## 4. STRIDE pass

### Spoofing

| # | Threat | Mitigation today | Residual risk |
|---|---|---|---|
| S1 | Attacker impersonates Proton's `mail-api.proton.me` to harvest credentials / inject malicious modulus. | TLS via Android system trust store; SPKI pins for ISRG Root X1 + X2 enforced via `CertificatePinner` (captured 2026-05-24, release-gated); DNS guard restricts to `*.proton.me`. | **Low.** An attacker must compromise the ISRG root CA itself or install a rogue CA on the device. Modulus pinning (S2) provides a second layer independent of TLS. |
| S2 | Attacker swaps the SRP `Modulus` to a backdoored value, defeating SRP entirely. | OpenPGP cleartext-envelope decoder peels the modulus; `BouncyCastleProtonModulusVerifier` verifies against the pinned Proton SRP signing key (`proton_srp_signing_key.asc`). On `INVALID` or `NO_SIGNER_KEY`, login aborts. Validated against live API on 2026-05-24. | **Low.** Attacker must also defeat TLS (S1) to inject a fake modulus. |
| S3 | Malicious app on the device registers an `AccountAuthenticator` with the same type and prompts the user for credentials. | `android:accountType="io.pcontacts.account"` is unique to our installation; AccountManager enforces uniqueness per (package, type). | Low — Android system blocks the duplicate registration. Verify with `adb dumpsys account`. |

### Tampering

| # | Threat | Mitigation today | Residual risk |
|---|---|---|---|
| T1 | Attacker modifies a stored Contact Card before it reaches us, swapping the encrypted vCard for one signed by the attacker's key. | Per-card signature verification on SIGNED and ENCRYPTED_AND_SIGNED cards; failure marks `is_verified=false`; UID properties from non-SIGNED cards are discarded (Plan §10.3). | Low — requires Proton compromise. Detection ships as a future Settings UI counter ("X contacts could not be verified"). |
| T2 | Attacker tampers with the local Room DB (e.g. flipping `content_hash` to bypass the idempotency skip and force unwanted writes). | App sandbox UID isolation; `android:allowBackup="false"` keeps the DB out of cloud backups. | Medium on rooted devices. SQLCipher is explicitly deferred (ADR-0008) because the DB holds no plaintext content — worst case a tamperer forces extra ContactsContract writes, no data loss. |
| T3 | Attacker tampers with the on-wire `Cards[]` payload (insert / drop / reorder). | Per-card signature verification; CLEAR_TEXT cards have no protection but only carry non-sensitive UID. | Low. |
| T4 | Attacker swaps the BouncyCastle .aar at build time. | Gradle wrapper integrity check in CI; `gradle/libs.versions.toml` pins exact versions; checksums via Gradle's resolution. | Medium — depends on the user's Maven Central trust. Mitigation: reproducible builds (deferred). |

### Repudiation

Out of scope at this size. The app has no audit log; the only
write-side artefacts are ContactsContract rows owned by us
(timestamped by the system) and Room rows.

### Information disclosure

| # | Threat | Mitigation today | Residual risk |
|---|---|---|---|
| I1 | Decrypted contact content lands in `Log.*` / `println` / `System.out.*` and gets harvested via `logcat`. | Custom `PcontactsSensitiveLog` Lint rule fails the build on direct `Log.*` calls outside `:core:logging` / `:app.logging`; production logger sink (`RedactingLogger`) strips fields named `token`, `password`, `passphrase`, etc.; the `:app` `AndroidLogcatSink` is the single sanctioned bridge to `android.util.Log`. | Low — Lint is mechanical; the per-field redaction list is the soft spot (a misnamed field could slip through). |
| I2 | Decrypted vCard plaintext is persisted to disk (Room, SharedPreferences, file cache). | ADR-0007 — explicit "never persisted" rule. Engine holds plaintext only on the heap during a sync run. No file caches. | Low. |
| I3 | Tokens / keyPassword end up in a crash dump / process memory dump. | `EncryptedSharedPreferences` decrypts on read; we attempt to zero the temporary `CharArray` passphrase after key unlock (ADR-0009). The JVM cannot guarantee memory zeroization — the GC may have copied the array elsewhere. | **Medium.** A heap dump of a running process exposes the unlocked key. Defending against this requires native memory the JVM doesn't manage; out of scope. |
| I4 | Android auto-backup exfiltrates `EncryptedSharedPreferences` to Google Drive. | `android:allowBackup="false"` in the manifest + a `data_extraction_rules` XML that excludes the secret-bearing prefs. Asserted via a manifest-merger test (TODO — currently asserted by the manifest file itself). | Low. |
| I5 | Sync log + ContactsContract rows exfiltrated by another app holding `READ_CONTACTS`. | Standard Android permission model — user grants `READ_CONTACTS` to the apps they trust. We don't have a stronger boundary. | **Medium by design.** This is the whole *point* — pcontacts puts contacts in the system address book so other apps (SMS, Phone, Mail) can use them. The user opts in when they grant READ_CONTACTS to a given app. |
| I6 | Contact photo bytes (the inline `Photo.PHOTO` column) leak via `READ_CONTACTS` to other apps. | Same as I5 — by design. The photo is downscaled to ≤96KB JPEG before storing. | Acceptable. |
| I7 | Outbox stores decrypted contact content at rest (if three-way merge requires last-known server payload). | ADR-0018 mandates: if the payload is stored, it MUST be encrypted under the Keystore AEAD KEK (`pcontacts.kekv1`) before writing to Room. If the implementation avoids storing payloads (re-fetches on demand), this threat is moot. | **Low** if encrypted; **Medium** if the implementation stores plaintext payloads (which ADR-0018 forbids). |
| I8 | Unlocked signing key lingers in heap between outbox push retries. | The key is re-unlocked from `keyPassword` on demand for each push attempt; it is not held between retries. The per-attempt window is the same as a sync run (seconds). | Low — same exposure as I3, no worse. |

### Denial of service

| # | Threat | Mitigation today | Residual risk |
|---|---|---|---|
| D1 | Proton rate-limits the app into a thundering-herd retry loop and locks the account. | `FibonacciBackoffInterceptor` (1s/2s/3s/5s/8s, cap 5 retries); `RefreshingAuthenticator` single-flight; periodic sync runs once / 12h via `PeriodicSyncWorker`. | Low — backoff + interval prevent abuse. |
| D2 | Server returns Code 9001 (human-verification challenge) on every call. | `HumanVerificationInterceptor` throws `HumanVerificationRequiredException`; SyncAdapter maps to `numAuthExceptions` so the sync framework stops retrying. | Low — but the user has no in-app captcha flow yet (deferred to UI follow-up). |
| D3 | A malicious vCard fragment crashes the parser. | ez-vcard is wrapped in try/catch in `VCardMerger`; malformed fragments are logged + skipped; the rest of the contact's cards still merge. | Low. |
| D4 | Photo bytes that aren't actually an image crash `BitmapFactory`. | `PhotoDownscaler.downscale()` returns null on decode failure; the contact still writes without a photo. | Low. |
| D5 | A `ContactsContract.applyBatch` call exceeds the binder transaction limit. | `BatchPlanner` chunks at 450 ops + re-anchors back-references at chunk boundaries (ADR-0010). | Low — verified by `BatchPlannerTest`. |
| D6 | An attacker controlling the network drops the SPKI pin → handshake fails forever. | DNS guard rejects non-Proton hosts, so the handshake is to the real Proton anyway. Pinning failure is the right outcome (refuse to talk to an unverified peer). | Acceptable. |

### Elevation of privilege

| # | Threat | Mitigation today | Residual risk |
|---|---|---|---|
| E1 | An attacker with code execution in the app sandbox reads keyPassword + decrypts every contact. | Standard Android sandbox boundary; keyPassword is double-wrapped (Keystore AEAD KEK + EncryptedSharedPreferences AES). Defeat requires breaking out of the sandbox. | Acceptable. |
| E2 | The `RefreshingAuthenticator` is tricked into refreshing a token for a different account (multi-account confusion). | Single-account MVP. AuthInterceptor + RefreshingAuthenticator both read from the same `InMemorySession`; no cross-account state. | Acceptable for MVP; revisit when multi-account ships. |
| E3 | A 9001 response loops forever, consuming network + battery. | Bounded retries (FibonacciBackoffInterceptor cap 5); 9001 thrown immediately to the caller without retry. | Low. |
| E4 | A rogue `AccountAuthenticator` issues a fake access token + tricks the SyncAdapter into syncing the wrong account. | The SyncAdapter reads its account from the system, not from a user-supplied source. | Low. |

---

## 5. Specific narratives

### 5.1 The MITM-on-modulus narrative (S2)

An attacker who controls the network AND can mint a TLS cert
trusted by Android (a state-level CA compromise or a malicious
enterprise CA installation) could attempt to return a backdoored
`Modulus` value on `/auth/info`. SRP's security depends on the
modulus being prime + correctly structured; a malicious modulus
leaks the verifier with high probability.

**Mitigation active**: the ADR-0014 pinned Proton SRP signing
key is shipped as `proton_srp_signing_key.asc` in `:core:crypto`
resources. `BouncyCastleProtonModulusVerifier` verifies the
modulus's OpenPGP cleartext-signed envelope against this key.
On `INVALID` (tampered modulus) or `NO_SIGNER_KEY` (resource
missing), login aborts. Validated against the live Proton API
on 2026-05-24 — the pinned key matches the key Proton uses to
sign the modulus in production.

### 5.2 The cross-app contact harvesting narrative (I5)

This is by design but worth surfacing: any app the user grants
`android.permission.READ_CONTACTS` to can read every contact
pcontacts writes to the system Contacts provider. SMS apps, dialers,
mail clients, social apps — every one of them gets the full set.

This is the same threat surface every account-syncing app has
(Google Contacts, Exchange, CardDAV clients, etc.). The user
opts into it explicitly when they install pcontacts and grants
READ_CONTACTS individually to each consuming app.

**No mitigation**. The whole point of the app is to put contacts
where other apps can use them. If you want decrypted contacts
that no other app can read, the right tool is Proton's own
Android Mail app's contact picker (which doesn't expose to
ContactsContract).

### 5.3 The lost-device narrative

User loses an unlocked device with the app in the foreground:

- The unlocked PGP user key may already be in heap (if a sync just
  ran). Plaintext vCards may still be in memory. Worst case window:
  the few seconds of an active sync run plus whatever the GC hasn't
  reclaimed.
- `EncryptedSharedPreferences` is decryptable by any code running
  with our UID — so any app with our package ID can read tokens +
  keyPassword. Mitigation: app-sandbox UID isolation.
- `RawContacts` rows are visible to every READ_CONTACTS-holding
  app on the device.

User loses a locked, screen-locked device:

- `EncryptedSharedPreferences` master key is backed by the
  Keystore. On API 23+ the Keystore key requires the device be
  unlocked at least once after boot before its key material
  becomes usable. So a powered-off / freshly-rebooted lost
  device protects the keyPassword via the lockscreen.
- Bypass: a fingerprint / face-unlock spoof. We don't defend
  against that — the threat model is the device's lockscreen.

**Mitigation hook**: the user can sign out remotely via the
Proton web UI (Sessions → Revoke). Our `/auth/refresh` token
becomes invalid immediately; subsequent sync attempts fail with
401, the `numAuthExceptions` counter trips, and the sync
framework stops trying.

---

## 6. Mitigations summary

Implemented:
- SecretStore with double-wrapping (Keystore AEAD KEK + EncryptedSharedPreferences).
- Keystore alias deletion on `SecretStore.logout()`.
- `android:allowBackup="false"` + data_extraction_rules XML.
- DNS guard (`ProtonHostDnsGuard`), SPKI certificate pinning (`ProtonCertificatePins` — ISRG Root X1 + X2, release-gated).
- Per-Card signature verification with `is_verified=false` on failure
  (no silent drops).
- `caller_is_syncadapter=true` on every ContactsContract write URI.
- Delete-and-reinsert child Data rows (preserves aggregate user state).
- Single-flight `/auth/refresh` mutex; bounded retry (responseCount > 1 → null).
- Fibonacci backoff for 429 (cap 5 retries); Retry-After honoured.
- 9001 surfaces as typed exception, never auto-retried.
- Custom Lint rule blocking `Log.*` / `println` outside the sanctioned
  bridge.
- ADR-0015 forbidden-dependency Gradle task wired into CI.
- R8 enabled for release builds with ProGuard rules covering BC, Room,
  Retrofit, kotlinx-serialization, ez-vcard, WorkManager,
  AbstractAccountAuthenticator, AbstractThreadedSyncAdapter.

Implemented (ADR-0017/0018):
- Outbox stores only `payload_hash` and metadata — no decrypted content
  at rest; re-fetches from ContactsContract on demand for each push.
- Signing key re-unlocked per sync run, not held between retries.
- Outbox wiped on logout alongside SecretStore and Room mapping
  (`LogoutOrchestrator` step 3).

Deferred (tracked):
- Instrumented ContactsContract tests on an emulator pipeline
  (aggregation behaviour, deletion tombstones, photo round-trip).
- Reproducible-build CI gate (diffoscope).
- OWASP dependency-check CI task (Dependabot covers update PRs but not blocking CI on known CVEs).
- Compose UI tests for the login + settings screens.
- Manifest-merger test asserting `allowBackup=false` +
  `debuggable=false` on release builds.
- Full license-scan plugin in addition to the
  forbidden-group check (the latter doesn't catch
  non-GPL-3-compatible licenses on allowed groups).

Accepted residual risks:
- Enterprise-installed CAs on the device can still MITM if they
  chain to a trusted root outside ISRG. The SPKI pins reject
  non-ISRG chains, but Android 7+ user-installed CAs are not
  trusted by default for release builds (network_security_config).
- No defence against heap memory exfiltration on a rooted device.
- No defence against other READ_CONTACTS-holding apps reading
  synced contacts (by design).
- No defence against the user installing a malicious keyboard /
  IME / accessibility service that scrapes the lockscreen.

---

## 7. Reporting a security issue

For now: open a private GitHub issue with the `security` label,
or email the maintainer at the address in `git log -1 --format=%ae`.
Do NOT file as a public PR.

If a fix requires a coordinated disclosure, expect a 30-day
embargo from first acknowledgement; longer if multiple parties
are affected.
