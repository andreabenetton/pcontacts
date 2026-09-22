# ADR-0009: Secrets storage — EncryptedSharedPreferences + Keystore AEAD, no backup

- **Status:** Accepted (amended 2026-09-22 — direct Keystore AES-GCM, durable wipe; see *Amendment*)
- **Date:** 2026-05-22
- **Deciders:** project owner
- **Related:** ADR-0002, ADR-0008

## Context

After login, the app holds material that must not leak:

| Item | Why sensitive |
|---|---|
| `UID` (Proton session ID) | Tied to active session; ex-filtration enables targeted abuse on the user's account. |
| `AccessToken` | Bearer credential — directly authenticates as the user. |
| `RefreshToken` | Long-lived bearer credential. |
| `keyPassword` (bcrypt-SHA512 of mailbox password + KeySalt) | Unlocks the user's OpenPGP private keys. Equivalent in risk to the mailbox password itself. |
| Unlocked OpenPGP private key material | The keys to all encrypted contacts and mail. |

Threat actors of concern:

1. **Local malware / abusive apps** — another app on the same device exploiting Android sandbox bypasses, accessibility services, or shared-storage gaps.
2. **Physical access to a locked device** — adversary with the device but not the unlock credential.
3. **Backup ex-filtration** — Android auto-backup uploading our app data to the user's Google account.
4. **Memory snapshots / debugger attachment** — debugger or `ptrace` against a running app.

Defenses available on modern Android (API 26+):

- **EncryptedSharedPreferences (AndroidX security-crypto)** — AES-256-GCM with a master key in the Android Keystore. Per-app, sandboxed.
- **Android Keystore + StrongBox (where available)** — hardware-backed key with optional user-authentication binding.
- **`allowBackup="false"`** — opts out of auto-backup entirely.
- **`android:debuggable="false"`** — blocks `jdb` / `gdb` attachment on release builds.
- **`android:hardwareAccelerated="true"` + cleartext-network disable** in manifest.
- **No `READ_LOGS` permission, no `Log.*` of sensitive material** — ADR-0015 enforces.
- **Wipe on `onTrimMemory(TRIM_MEMORY_UI_HIDDEN)`** — best-effort zeroization of unlocked-key heap buffers when the app moves to background.

`keyPassword` storage requires a second layer because EncryptedSharedPreferences alone uses a single per-app master key; we want the additional ability to require user presence (biometric) for the highest-risk material in a future iteration.

## Decision

**Storage layout:**

- **`EncryptedSharedPreferences` file `auth_prefs`** (AES-256-GCM master key, default StrongBox if available):
  - `UID`
  - `access_token`
  - `refresh_token`
  - `key_password_wrapped` — `keyPassword` bytes AEAD-encrypted under a second Keystore key (alias `pcontacts.kekv1`), AES-GCM. The wrap key is what becomes biometric-bound in a future iteration; today it has no user-auth binding so background sync works.
  - `account_local_id`, `user_id` (non-secret metadata)
- **`AndroidManifest.xml`** declares `android:allowBackup="false"` on the application element.
- **No `keyPassword` plaintext ever touches `SharedPreferences`** — only the wrapped form.
- **In-memory unlocked private keys** live in a `SecureKeyHolder` that nulls/zeroizes its backing `ByteArray` on `clear()` and on `onTrimMemory(TRIM_MEMORY_UI_HIDDEN)`.
- **Release builds** are non-debuggable and have R8 minification on. Stack traces in `last_error` (ADR-0008) record class name + opcode only, never field values.
- **Logout** revokes the server session (DELETE `core/v4/auth`), then wipes `auth_prefs`, deletes the Keystore aliases, removes the Android account (cascades to RawContacts deletion), drops the Room DB.

**Out of MVP, but planned:** biometric unlock of `pcontacts.kekv1` via `androidx.biometric` for the wrap key. The default is unbound so that background sync (no user present) works; users who want stronger guarantees can enable biometric gating in Settings post-MVP.

## Alternatives considered

- **Plain `SharedPreferences`.** Rejected — anyone with file-system access (rooted device, ADB backup) reads tokens.
- **`SQLCipher` for everything.** Rejected — sledgehammer; ADR-0008 already explains why Room is unencrypted for mapping data.
- **Force biometric on every sync.** Rejected — breaks background periodic sync entirely.
- **Token storage in `Account.userData` via `AccountManager`.** Rejected — `userData` is plaintext-on-disk and the wrong place for refresh tokens.

## Consequences

- All secret reads/writes go through a single `SecretStore` interface in `:core:storage`. Direct `SharedPreferences` access in other modules is forbidden (detekt rule).
- `allowBackup="false"` means the user gets no cross-device account transfer. They re-add the Proton account on the new device. Acceptable.
- We own the `pcontacts.kekv1` Keystore alias lifecycle: created by the first write after sign-in, deleted (and verified gone) on logout. Nothing rotates it.
- Memory zeroization is best-effort on the JVM. We document this limitation in `docs/THREAT_MODEL.md`. Memory snapshots remain a residual risk against well-resourced adversaries.

## Validation

- Instrumented test: write a token via `SecretStore`, force-kill the app, restart, read back — value matches.
- Instrumented test: logout, then assert `auth_prefs` file is absent and Keystore alias does not exist.
- Static check: no `SharedPreferences` constructor in any module other than `:core:storage`.
- Manifest review on release: `allowBackup="false"`, `debuggable="false"`, no `tools:replace` overriding either.

## Implementation status

Shipped:

- `SecretStore` interface + `InMemorySecretStore` (tests) + `EncryptedSecretStore` (production) in `core/storage/src/main/kotlin/io/pcontacts/core/storage/`.
- `KeystoreAesGcmKek` (the `SecretCipher`) seals every value under the Keystore alias `pcontacts.kekv1` (AES-256-GCM; StrongBox attempted on API 28+, TEE fallback). Wire format `[IV 12B][ciphertext+tag]`. `unwrap` never provisions a key; `delete` verifies the alias is gone.
- `EncryptedSecretStore` stores each sealed value base64-encoded in the private preferences file `pcontacts_auth_v2`; every write and the logout wipe use a synchronous `commit()` and throw on refusal. The pre-2.0 `EncryptedSharedPreferences` file and the androidx master key are purged on first start (users sign in again once).
- Manifest: `android:allowBackup="false"`, `android:dataExtractionRules` excludes the secret-bearing prefs; release build is `android:debuggable="false"` (the `release` buildType in `app/build.gradle.kts` pins it).
- `LogoutOrchestrator` calls `SecretStore.logout()` as step 4 of the sign-out chain; on success every secret is zeroed and the Keystore alias is deleted.
- Custom `PcontactsSensitiveLog` Lint rule enforces no `android.util.Log` / `println` / `System.out.*` calls outside `:core:logging` and the sanctioned bridge in `:app.logging.AndroidLogcatSink`.

Deferred:

- The instrumented "force-kill + restart, value matches" round-trip needs an emulator pipeline (no such CI lane yet).
- Manifest-merger golden-file test asserting `allowBackup=false` + `debuggable=false` on the release variant.
- Static check that flags direct `SharedPreferences` constructors outside `:core:storage` (currently only the lint rule for `Log.*` exists; detekt is declared in libs but not yet wired).

## Amendment (2026-09-22): direct Keystore AES-GCM, durable wipe, alias lifecycle

Strengthens the decision; nothing below permits less than before.

- **Storage.** `androidx.security:security-crypto` (a deprecated
  1.1.0-alpha dependency) and Tink are removed. Every value —
  UID, tokens, human-verification token, keyPassword — is sealed by
  the Keystore key `pcontacts.kekv1` (AES-256-GCM, `[IV 12B][ct+tag]`)
  and stored base64-encoded in the private preferences file
  `pcontacts_auth_v2`. One AEAD layer replaces two: the second
  layer defended against the same adversary (code running as our
  UID) and its master key was never deleted at logout. Key names
  are compile-time constants, so their being readable reveals
  nothing.
- **Durability.** Every write and the logout wipe use `commit()`;
  a refused commit throws `SecretStoreWriteException`. `logout()`
  clears and commits the file first, then deletes the alias and
  verifies it is gone. `LogoutOrchestrator` treats a failure as a
  failed sign-out: the Android account stays and the UI reports it.
- **Reads fail closed.** Ciphertext that no longer opens (alias
  deleted, blob damaged) reads as absent and routes to the existing
  "sign in again" path; `unwrap` never provisions a replacement key.
- **Alias lifecycle.** Created by the first write after sign-in;
  deleted and verified at logout; never rotated. StrongBox is
  attempted on API 28+ with a TEE fallback. Existing TEE keys on
  already-installed devices remain valid.
- **Legacy purge.** On first start after the update the
  `pcontacts_auth_prefs` file and the `_androidx_security_master_key_`
  alias are deleted without being read; the on-disk state is then
  exactly the secure default, and users of 1.x sign in once more (the
  Android account and synced contacts persist).
- **Backup.** Unchanged: `allowBackup="false"` and the extraction
  rules exclude every `sharedpref`, asserted by
  `verifyManifestInvariants`.
