# CLAUDE.md

## Purpose
Use this file as the default implementation context for this repo. Do not restate the architecture in every prompt — read it from ADRs and from the implementation plan. Optimize for correctness, security, reproducibility, and respect for the user's privacy stance.

This file is shaped from the dcs project's CLAUDE.md, adapted for an Android single-app codebase. Where dcs's rules don't apply (multi-app monorepo, .NET backend, flow contracts, OpenAPI, podman) they were dropped, not silently translated.

---

## Repo stance
This repo is:
- **single-app Android** (Kotlin, Gradle, multi-module — see ADR-0011)
- **GPL-3.0-only**, with SPDX headers on every source file
- **F-Droid first**, sideload-friendly; **no Google Play Services, no telemetry, no proprietary blobs**
- **client-side-crypto-only**: the Proton API decrypt happens on-device; decrypted contact data is never logged, never sent off-device, never persisted in MVP
- **unofficial-API consumer**: every claim about Proton's API is marked with `[V]` / `[U]` / `[A]` / `[D]` (verified / unverified / assumption / discouraged)

---

## Source precedence
For implementation work, use this order:

1. `docs/adr/NNNN-*.md` — architecture decisions. Contracts of the codebase.
2. The implementation plan (lives outside the repo at `/home/user/.claude/plans/act-as-a-staff-lovely-squid.md`) — the phased roadmap, risk register, and verification plan.
3. `NOTICE` — what we attribute and to whom.
4. The Proton/WebClients reference (`https://github.com/ProtonMail/WebClients` at the pinned commit recorded in `docs/API_RESEARCH.md` once that lands) — the executable specification we port from.
5. Existing code in this repo.

Rules:
- ADRs win for architecture. If existing code conflicts with an ADR, the code is wrong (or the ADR needs a superseding ADR).
- Proton's web client is the spec for protocol shape and crypto behavior; our Kotlin port must match it bit-exact where it must (see ADR-0013).
- An undocumented Proton behavior is **never** assumed safe — mark it `[U]` and design a fallback.

---

## Non-negotiable architectural rules

These are the load-bearing invariants. Every one corresponds to an ADR; read the ADR for the rationale.

### License + attribution (ADR-0001)
- License is **GPL-3.0-only**. Every source file carries an SPDX header.
- Files materially derived from ProtonMail/WebClients carry an additional `SPDX-FileCopyrightText` line crediting Proton AG, naming the upstream file, and pinning the upstream commit.
- New runtime dependencies must be GPL-3.0-compatible. The release build fails if they aren't (ADR-0015).

### Crypto strategy (ADR-0002, ADR-0013, ADR-0014)
- All crypto is **native Kotlin** in `:core:crypto`: BouncyCastle for OpenPGP, ported SRP-6a, ported bcrypt-SHA512.
- **No JS engine is ever bundled.** The app never executes JavaScript.
- Every change to `:core:crypto` runs the captured-vector test suite (`tools/vectors/`) and must pass.
- The Proton SRP modulus signing public key is pinned (`core:crypto/src/main/resources/proton_srp_signing_key.asc`); modulus signature verification is mandatory before SRP arithmetic; on verification failure, login aborts.

### Decrypt client-side only (ADR-0007)
- Always pull encrypted `Cards[]` and decrypt locally.
- The app **never** calls `GET contacts/v4/contacts/export`. A CI grep fails the build if that path appears in any source file outside ADR-0007.
- Decrypted vCard bytes live only on the heap during a sync; they are not logged, not persisted, not transmitted off-device.

### Secrets storage (ADR-0009)
- All secret reads/writes go through the `SecretStore` interface in `:core:storage`.
- Direct `SharedPreferences` constructor calls outside `:core:storage` are forbidden (detekt rule).
- `keyPassword` is wrapped under a Keystore AEAD key (`pcontacts.kekv1`) before it touches EncryptedSharedPreferences.
- Manifest invariants on release builds: `android:allowBackup="false"`, `android:debuggable="false"`. Asserted in a manifest-merger test.

### ContactsContract writes (ADR-0010)
- Every write to `RawContacts` / `Data` URIs uses `?caller_is_syncadapter=true`. The helper that builds these URIs is the **only** way to construct them in `:core:contacts-writer`.
- Update path is **delete-and-reinsert child `Data` rows** under a stable `RawContacts._ID`. Never delete the `RawContacts` itself on update (preserves user-owned aggregated state like starred / ringtone).
- `applyBatch` is chunked to ≤ 450 ops.

### No telemetry, no Google Services (ADR-0015)
- No `com.google.android.gms`, no `com.google.firebase`, no analytics SDK, no remote configuration, no kill-switch.
- The release build's dependency-license report task fails if a disallowed group, artifact, or license appears in the resolved graph.
- `OkHttpClient` is constructed only in `:core:proton-api`; its DNS resolver rejects hosts not matching `*.proton.me`.
- Custom Android Lint rule `pcontacts.SensitiveLog` fails the build on any `android.util.Log`, `println`, or `System.out.*` call inside `:core:*` or `:feature:*`. Use `:core:logging`'s `Logger` interface instead — the production implementation strips sensitive fields.

### Module boundaries (ADR-0011)
- `:feature:*` must not depend on `:core:crypto` or `:core:proton-api` directly. They reach those layers through `:core:sync`.
- No module depends on `:app`.
- Pure-JVM modules (`:core:crypto`, `:core:proton-api`, `:core:proton-contacts`) must remain testable without an emulator.

### Verification markers
- Every claim about Proton's API or `@protontech/crypto` behavior carries a marker in any new ADR, doc, or code comment:
  - `[V]` Verified from ProtonMail/WebClients source.
  - `[U]` Unverified — present in code but mechanism not fully knowable from JS/TS alone.
  - `[A]` Assumption — must be validated against a real Proton account.
  - `[D]` Discouraged or out of scope.
- If a code path depends on a `[U]` or `[A]`, it carries a fail-closed branch and a logged (non-sensitive) signal.

---

## Documentation conventions

| Type | Pattern | Example |
|---|---|---|
| ADR | `NNNN-lowercase-kebab.md` (four-digit) | `0009-secrets-storage.md` |
| Other docs | `UPPER_SNAKE.md` at the doc's level | `THREAT_MODEL.md` |

Rules:
- ADRs are numbered four-digit, sequential, never reused. The number is permanent across supersession.
- Do **not** rename existing ADRs. If a decision changes, write a new ADR that supersedes the old one and update the old one's `Status` header.
- An ADR is one decision. If it grows beyond ~2 pages, it's probably two ADRs.
- The ADR index lives at `docs/adr/README.md`. Every new ADR adds its row.
- A new ADR ships in the same commit as the code that enacts it.

---

## Git discipline

After each logical unit of work:
- create a git commit
- push to the current branch

If push cannot be completed because of missing remote, credentials, branch protection, or environment limits:
- say so explicitly
- do not claim the push succeeded

Commit messages must be short, specific, and scoped to the actual change. Do not leave completed logical units of work uncommitted.

**Do not add a `Co-Authored-By` trailer to any commit message.**

### Multi-fix prompts
When a single prompt asks for **more than one unrelated fix** (different files, different bugs, different ADRs, different concerns — not the natural sub-tasks of one feature), do not bundle them into a single commit. Instead, for each fix in turn:

1. implement only that one fix
2. add or update only the tests directly related to it
3. run the impacted tests; verify they pass
4. create one commit scoped to that fix (with a commit message describing only it)
5. push, then move to the next fix

Each fix becomes one commit. Each commit is independently reviewable, revertable, and bisectable. A multi-fix prompt produces N commits, not one.

Related sub-tasks of the same fix (e.g., a code change plus its test plus a docstring update plus a doc cross-reference) belong in the same commit — they are not "different fixes". The discriminator is whether the changes share a single root cause, ADR, or feature.

Do not bundle "while I'm here" cleanups into a fix commit. If unrelated drift is discovered mid-fix, either (a) note it explicitly and defer it, or (b) handle it as its own follow-up commit after the in-scope fix is committed.

### Multi-module prompts
When a prompt's work spans more than one Gradle module (e.g., `:core:proton-api` + `:core:proton-contacts` + `:feature:onboarding`), do not bundle it into a single commit — even when it's one coherent feature. For each module in turn:

1. implement only that module's portion
2. add or update only the tests directly related to it
3. run that module's tests; verify they pass
4. create one commit scoped to that module
5. push, then move to the next module

Shared edits (an ADR, a NOTICE update, a version-catalog bump) that enable only **one** module's commit may ride with that commit. Shared edits that enable **more than one** module's commit go into their own preceding commit. Order from lowest-level to highest: `:core:crypto` and `:core:proton-api` before `:core:sync` before `:feature:*` before `:app`.

### Debugging hygiene

When chasing a bug across multiple commits, **do not squash the chain into a single "fix X" commit**. Each independent root cause peeled back during the investigation deserves its own commit, even when the surface symptom is the same. Squashing distinct fixes into one commit loses bisectability, makes reverts blast-radius bigger than they should be, and hides the diagnostic narrative future-you will want when the same symptom resurfaces.

What MUST be cleaned up before commit:

- **Diagnostic instrumentation added while chasing the bug.** Examples: `Log.d` traces sprinkled in hot paths, `println` dumps of payloads, transient `if (BuildConfig.DEBUG) { … }` shims, dispatcher constructor logs enumerating registrations. These served their purpose finding the bug; leaving them in pollutes the log surface (and, in this project, risks tripping the `pcontacts.SensitiveLog` Lint rule).
- **Throw-away one-shot fixtures.** Hardcoded test ids, sample JSON pasted from a curl session against a live Proton account, `if (DEBUG) return early` shortcuts.
- **Commented-out code** from earlier hypotheses.

What is NOT diagnostic noise (keep it):

- A **warning log on a real fallback path** the production code can take (e.g., "signature verification failed for card; retaining decrypted data with isVerified=false"). That's a permanent operational signal.
- A **catch-block log of swallowed exceptions** that previously surfaced silently. Silent swallowing is a bug magnet — the structured (non-sensitive) log is the fix.
- A **structured info log on a one-shot startup or sync-start path** ("sync started for account=… contacts=…"). Fires once per sync, not per contact, and contains no contact content.

Mechanically: either fold the cleanup into the same commit as the fix, OR add it as a follow-up commit before pushing the chain. Do not push diagnostic noise "to clean up later" — later rarely comes.

---

## Anti-patterns

Do not introduce:

- decrypted vCard content in `Log.*`, `println`, `System.out.*`, exception messages, crash dumps, or DB rows
- token / passphrase / private-key material in `SharedPreferences` outside `:core:storage`
- direct `SharedPreferences` constructor calls outside `:core:storage`
- direct `ContentResolver.applyBatch` calls outside `:core:contacts-writer`
- a `RawContacts` / `Data` URI built without `caller_is_syncadapter=true`
- a call to `GET contacts/v4/contacts/export`
- a JS engine, embedded interpreter, WebView for protocol work
- a Google Play Services or Firebase dependency
- a network call to a host not matching `*.proton.me` from `:core:proton-api`
- a new SRP modulus path that doesn't verify the pinned signature
- a Proton API claim without a `[V]`/`[U]`/`[A]`/`[D]` marker
- a runtime fetch of a pinned key, certificate, or fingerprint
- an ADR renumbering or rename to satisfy aesthetic preference
- a commit that bundles unrelated fixes
- a commit message that says "various fixes" or "WIP"
- a `Co-Authored-By` trailer

---

## Completion checklist

Use this checklist internally before closing work. Do not reproduce it in responses unless items are missing or need explicit callout.

- ADR(s) for affected layers respected
- if behavior conflicts with an ADR: superseding ADR written and committed
- verification markers present on every new claim about Proton's API or crypto
- decrypted contact data never logged or persisted (sensitive-log Lint passes)
- `?caller_is_syncadapter=true` on every ContactsContract write
- no new dependency violates the ADR-0015 allowlist
- no host outside `*.proton.me` is contacted from `:core:proton-api`
- module-boundary rules respected (`:feature:*` does not reach `:core:crypto` directly)
- unit tests updated; relevant module's tests pass
- captured-vector tests pass for any `:core:crypto` change
- instrumented tests updated for any `:core:contacts-writer` change
- migration test present for any Room schema change
- ADR added/updated for any architectural shift
- `NOTICE` updated for any new GPL-3.0-derived code or new third-party dep
- changes committed with a short scoped message; no `Co-Authored-By`
- changes pushed, or push limitation explicitly reported
- secrets stance preserved (no token/private-key bytes outside `:core:storage`)

---

## Expected delivery format

For minor fixes, a short summary and commit status are sufficient.

For significant work, include:

1. What changed
2. Why
3. ADRs affected (new, superseded, or referenced)
4. Modules touched
5. Tests added / updated
6. Verification markers introduced (`[V]`/`[U]`/`[A]`) and what would validate any `[U]` or `[A]`
7. Security / privacy implications (sensitive-data paths, new permissions, new network endpoints)
8. F-Droid / reproducible-build implications
9. Commit and push status
10. Known follow-ups deferred
11. Remaining implementation work implied by the change

Never present work as complete while known consumer mismatches (e.g., "the live Proton API returns `Foo` but our DTO declares `Bar`") remain unmentioned. Never claim commit or push completion if it did not actually happen.
