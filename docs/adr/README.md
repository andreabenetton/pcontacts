# Architecture Decision Records

ADRs follow the [Michael Nygard format](https://cognitect.com/blog/2011/11/15/documenting-architecture-decisions). Each one captures one decision, with the context that motivated it, the chosen direction, the alternatives considered, and the consequences we accept.

Status values: **Proposed**, **Accepted**, **Superseded by NNNN**, **Deprecated**.

If a decision changes, do not edit the existing ADR — write a new one that supersedes it, and update the old one's status header.

## Index

| ADR | Title | Status |
|---|---|---|
| [0001](0001-license-gpl-3-only.md) | License — GPL-3.0-only | Accepted |
| [0002](0002-native-kotlin-crypto.md) | Crypto strategy — native Kotlin (BouncyCastle + ported SRP/bcrypt-SHA512) | Accepted |
| [0003](0003-distribution-fdroid-first.md) | Distribution — F-Droid first, sideload-friendly | Accepted |
| [0004](0004-account-authenticator-sync-adapter.md) | System integration — AccountAuthenticator + SyncAdapter (+ WorkManager) | Accepted |
| [0005](0005-vcard-library-ezvcard.md) | vCard library — ez-vcard, no ical.js port | Accepted |
| [0006](0006-mvp-read-only-single-account.md) | MVP scope — read-only, single account | Accepted |
| [0007](0007-client-side-decryption-only.md) | Decrypt client-side only — never use server-side export | Accepted |
| [0008](0008-room-mapping-database.md) | Local mapping store — Room for ProtonID ↔ RawContactID | Accepted |
| [0009](0009-secrets-storage.md) | Secrets storage — EncryptedSharedPreferences + Keystore AEAD; no backup | Accepted |
| [0010](0010-contactscontract-write-strategy.md) | ContactsContract write strategy — delete-and-reinsert child rows | Accepted |
| [0011](0011-gradle-module-structure.md) | Gradle module structure — feature/core split | Accepted |
| [0012](0012-http-stack-okhttp-retrofit.md) | HTTP stack — OkHttp + Retrofit, single-flight refresh | Accepted |
| [0013](0013-crypto-test-vectors.md) | Crypto correctness — capture vectors from `@protontech/crypto` once | Accepted |
| [0014](0014-modulus-pinning.md) | SRP modulus pinning — verify each modulus against Proton's signing key | Accepted |
| [0015](0015-no-telemetry-no-google-services.md) | No telemetry, no Google Services, no proprietary deps | Accepted |
| [0016](0016-no-accountmanager-token-retrieval.md) | No AccountManager token retrieval — getAuthToken returns re-auth intent | Accepted |
| [0017](0017-bidirectional-sync-scope-and-policies.md) | Bidirectional sync — scope and policies | Accepted |

## Template

See [`template.md`](template.md) for new ADRs.

## Conventions

- ADR filenames: `NNNN-kebab-case-title.md`, four-digit zero-padded sequence.
- Number is permanent; never reuse a number, even if an ADR is deprecated.
- Keep an ADR to ~1 page. If it grows, the decision is probably two decisions.
- Cite Proton/WebClients source paths with the commit hash pinned in [`docs/API_RESEARCH.md`](../API_RESEARCH.md).
- Use the verification markers from the implementation plan when relevant: `[V]` verified, `[U]` unverified, `[A]` assumption, `[D]` discouraged.
