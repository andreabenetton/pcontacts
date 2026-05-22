# ADR-0011: Gradle module structure — feature/core split

- **Status:** Accepted
- **Date:** 2026-05-22
- **Deciders:** project owner

## Context

The plan touches several distinct subdomains: HTTP+auth plumbing, OpenPGP+SRP crypto, vCard parsing, ContactsContract writing, sync orchestration, local DB, UI flows. Putting all of this in a single `:app` module would:

- Force the entire codebase to recompile on every UI change.
- Make it hard to enforce dependency rules (e.g. "the UI must never call BouncyCastle directly").
- Make unit testing harder (more transitive Android-platform classes in the test classpath).

A multi-module split lets us:

- Run pure JVM unit tests against `:core:crypto`, `:core:proton-api`, `:core:proton-contacts` without Robolectric or instrumentation.
- Enforce architectural boundaries via Gradle dependency declarations.
- Parallelize compilation.

We are not at the scale where a `buildSrc` convention plugin per layer pays off; we use Gradle version catalogs (`libs.versions.toml`) and a small `build-logic/` convention plugin (one for Android library modules, one for pure-JVM modules) — that's it.

## Decision

**Module graph:**

```
:app
  └── :feature:onboarding   (Login, 2FA, key-unlock screens)
  └── :feature:settings     (Sync interval, sign out)
  └── :core:sync            (SyncEngine orchestration)
        └── :core:proton-api
              └── :core:crypto    (BouncyCastle + SRP + bcrypt-SHA512)
              └── :core:logging   (RedactingLogger)
        └── :core:proton-contacts (DTOs, Card split/merge, vCard wrappers)
              └── :core:crypto
              └── :core:logging
        └── :core:contacts-writer (ContactsContract ops)
              └── :core:logging
        └── :core:storage         (Room + EncryptedSharedPreferences)
              └── :core:logging
```

**Module types:**

- `:core:crypto`, `:core:proton-api`, `:core:proton-contacts` — **pure JVM libraries** (`com.android.library` only where Android APIs are needed; prefer Kotlin/JVM modules). Crypto and the API surface should be testable without an emulator.
- `:core:contacts-writer`, `:core:storage`, `:core:sync`, `:core:logging` — **Android library modules**.
- `:feature:*` — Android library modules with UI dependencies.
- `:app` — application module.

**Conventions enforced via Gradle:**

- `:feature:*` must not depend on `:core:crypto` or `:core:proton-api` directly — they go through `:core:sync` or a feature-local repository.
- No module depends on `:app`.
- Cyclic dependencies fail the build (Gradle does this by default; we ensure no `compileOnly` workarounds reintroduce cycles).

**Build tooling:**

- Kotlin Gradle DSL (`.kts`) everywhere.
- Version catalog `gradle/libs.versions.toml`.
- `build-logic/` `included-build` with two convention plugins: `pcontacts.android.library`, `pcontacts.kotlin.library`. Each centralizes JVM target, Kotlin options, lint, detekt, and Java toolchain.
- Java toolchain pinned to 17.

## Alternatives considered

- **Single `:app` module.** Rejected — fast at first, painful later, terrible for test isolation.
- **One module per layer plus one per feature, with shared `:domain` and `:data`.** Rejected as over-engineered for a 4-developer-month scope.
- **Use `kmp` (Kotlin Multiplatform) for `:core:*`.** Rejected — KMP adds complexity (Native, iOS targets, source-set layout) we do not need; the app is Android-only by definition (`ContactsContract` is Android-only).

## Consequences

- New module = add to `settings.gradle.kts`, apply a convention plugin, declare dependencies. One-file-per-module overhead.
- Each `:core:*` module can have its own unit tests in pure JVM (fast feedback).
- The architecture forbids dependency leaks (e.g. `:feature:onboarding` cannot call `BouncyCastle` directly because it doesn't have `:core:crypto` on its classpath).
- The `build-logic/` included build is two short Kotlin files; no plugin marketplace dependency.
- R8/ProGuard rules live in `:app` and pull in module-level `consumer-rules.pro` files where modules need them (BouncyCastle reflection requirements live in `:core:crypto`).

## Validation

- `./gradlew :core:crypto:test` runs without an emulator and completes in < 10s.
- `./gradlew assembleDebug` runs in < 60s on a warm cache.
- A dependency-rule test (custom Gradle task or detekt rule) fails if `:feature:onboarding` directly references a `:core:crypto` symbol.
