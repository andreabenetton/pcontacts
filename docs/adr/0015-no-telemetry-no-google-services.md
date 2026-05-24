# ADR-0015: No telemetry, no Google Services, no proprietary dependencies

- **Status:** Accepted
- **Date:** 2026-05-22
- **Deciders:** project owner
- **Related:** ADR-0001, ADR-0003, ADR-0007, ADR-0009

## Context

The project's privacy stance is structural, not marketing. A user installs a Proton contacts client because they want privacy guarantees that the official Proton apps already provide — anything we add must not weaken those guarantees.

Specific properties to preserve:

1. **No data exfiltration** — the app does not send the user's contact data, tokens, or any derived information to any server other than `api.proton.me` (and only the requests the user's session actively initiates).
2. **No third-party analytics** — no Firebase Analytics, Crashlytics, Sentry, Amplitude, Mixpanel, AppsFlyer, etc.
3. **No remote configuration / kill-switch** — the app's behavior is determined by its compiled bytes, not by a server.
4. **No Google Play Services** — F-Droid main repo (ADR-0003) requires this. Also avoids the privacy concerns of Google Play Services' ambient data collection.
5. **No closed-source binary dependencies** — F-Droid won't ship them; we don't want them.

These properties must be **enforced**, not aspirational, because a contributor could (in good faith) add a "just analytics for crashes" dependency that violates the entire stance.

The redacting-logger requirement (no `Log.*` of tokens, passwords, signatures, vCard fields) lives here too, since it's a "do not leak sensitive data" rule that operates at the same layer.

## Decision

**Enforced at build time:**

1. **Dependency allowlist.** A Gradle task (`./gradlew :app:dependencyLicenseReport` + custom assertion) walks the resolved dependency graph at `assembleRelease` time. Fails if any artifact:
   - Matches the Google Play Services group `com.google.android.gms` or `com.google.firebase`.
   - Carries a known proprietary license (anything not in our allowlist: Apache-2.0, BSD-2-Clause, BSD-3-Clause, MIT, EPL-2.0, MPL-2.0, LGPL-2.1+, LGPL-3.0+, GPL-2.0+, GPL-3.0+, ISC, CC0-1.0, Unlicense).
   - Pulls a binary `.so` blob that wasn't built from source we ship.
2. **No telemetry endpoint allowlist.** `OkHttpClient` for `:core:proton-api` is constructed with a `Dns` resolver that rejects any host not matching `*.proton.me`. Tests assert this. The app has no other `OkHttpClient` instance; this is enforced by detekt rule "OkHttpClient must be constructed only in `:core:proton-api`".
3. **Manifest invariants** asserted in a manifest-merger test on release builds:
   - No `<meta-data android:name="com.google.android.gms.version">`.
   - No `<provider>` declarations from `com.google.firebase`.
   - `android:allowBackup="false"`.
   - `android:debuggable="false"` in release.
4. **Redacting logger.**
   - `:core:logging` exposes `Logger` interface. Production implementation strips fields matching regexes for `token`, `password`, `passphrase`, `signature`, `private[-_]?key`, `Bearer\s+\S+`, plus a list of explicit field names from DTOs (`AccessToken`, `RefreshToken`, `Data`, `Signature`, `PrivateKey`).
   - Custom Android Lint rule `pcontacts.SensitiveLog` fails the build on any `android.util.Log`, `println`, or `System.out.print*` call inside any `:core:*` or `:feature:*` module.
   - `Throwable.printStackTrace()` and `Throwable.toString()` are likewise flagged on auth/crypto/contacts call paths — exceptions get serialized as `class + opcode` only (no message body).
5. **BuildConfig flag `TELEMETRY_ENABLED = false`.** Compile-time constant. Any code branch behind `if (BuildConfig.TELEMETRY_ENABLED)` is dead code in our build by construction. The flag exists so a static reader can see the project's stance in one grep.
6. **README + F-Droid metadata** declare "No tracking, no telemetry, no Google Services" in user-facing language.

**Out of scope (explicitly):**

- Crash reporting that uploads anywhere. If a future ADR proposes opt-in local crash dumps (written to internal storage, surfaced via a Settings → "Share crash log" action), it must be a new ADR that supersedes this one's "no crash dumps to disk" implication, and must be opt-in with explicit user action per report.

## Alternatives considered

- **Opt-in Firebase Crashlytics.** Rejected — even opt-in, the SDK pulls in Google Play Services dependencies that break F-Droid.
- **Self-hosted Sentry instance.** Rejected — adds a server we must run, and a network call to a Proton-adjacent service that isn't `api.proton.me`. Either we ask users to trust us with crash data or we don't; we choose "don't".
- **Local-only crash log with manual share.** Considered, deferred. Not in MVP. Would be a new ADR.

## Consequences

- A clean PR cannot accidentally introduce telemetry or Google Play Services — the build fails before merge.
- Debugging production crashes relies on user-reported reproductions and on the local-only diagnostic data we expose in Settings (last sync error class+opcode, sync timing histogram with no per-contact data). Acceptable in this project's context.
- We own the Lint rule and the dependency-allowlist task. Both are small (each < 100 LOC of Kotlin/Groovy).
- F-Droid inclusion is unblocked by this ADR's enforcement work.

## Validation

- CI job: `./gradlew :app:dependencyLicenseReport` runs on every PR; non-zero exit on any disallowed dependency.
- CI job: instrumented test boots a release-flavor APK in an emulator with no network — startup succeeds without any DNS query for non-`proton.me` hosts (verified via VPN-style packet capture in CI).
- CI job: lint runs the `pcontacts.SensitiveLog` rule; non-zero exit on any violation.
- CI job: manifest-merger output for release flavor is asserted against a golden file that contains no GMS/Firebase entries.
- Manual: `apkanalyzer files list app-release.apk` shows no `META-INF/.*\.firebase\b` or `assets/google_*` resources.

## Implementation status

Four structural enforcement gates are shipped:

1. **Forbidden-dependency Gradle task** — `checkForbiddenDependencies` in the root `build.gradle.kts` walks every sub-project's resolved release runtime classpath and fails on any artifact whose group is in the blocklist (Google Play Services, Firebase, Play Integrity, Ads, Sentry, Bugsnag, AppsFlyer, Crashlytics, Fabric, Google Places). Marked `notCompatibleWithConfigurationCache` because it walks resolved configurations at execution time. Wired into the CI `assemble-debug` job so every PR exercises it.
1b. **License-compatibility gate** — Custom `checkLicense` task in `app/build.gradle.kts` uses Gradle's `ArtifactResolutionQuery` API to fetch POM files for the full transitive release classpath and fails if any artifact carries a license not on the allowlist in `config/allowed-licenses.json`. Wired into the CI `assemble-debug` job alongside `checkForbiddenDependencies`.
2. **`PcontactsSensitiveLog` Lint rule** — `tools/lint/.../SensitiveLogDetector.kt` (UAST detector) flags any `android.util.Log.*`, `println`, or `System.out.*` call outside the sanctioned bridge package `io.pcontacts.app.logging` and `:core:logging`. Wired into every Android module via `lintChecks(project(":tools:lint"))`. Failed Lint = failed build.
3. **DNS allowlist for the single OkHttpClient** — `ProtonHostDnsGuard` in `:core:proton-api` refuses to resolve hosts that don't match `*.proton.me` (localhost allowed for MockWebServer tests). Mechanical enforcement of CLAUDE.md's "no host outside *.proton.me" rule.

Plus R8 + ProGuard rules in `app/proguard-rules.pro` cover BouncyCastle reflection, kotlinx-serialization, Retrofit, Room KSP-generated impls, WorkManager, AbstractAccountAuthenticator, AbstractThreadedSyncAdapter. `isMinifyEnabled = true` on the release buildType so every assembleRelease exercises them.

Deferred:

- Manifest-merger golden-file test asserting no GMS/Firebase services or receivers slip in.
- Network-capture CI gate asserting startup makes zero DNS queries for non-`proton.me` hosts on a fresh emulator.
- `apkanalyzer files list` post-build assertion against a forbidden-file pattern list.

The CI workflow (`.github/workflows/build.yml`) runs `checkForbiddenDependencies` + `:app:checkLicense` + `:app:lintDebug` (which includes the Sensitive-Log rule) + `:app:assembleRelease` (R8) on every push / PR.
