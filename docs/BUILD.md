<!--
  SPDX-License-Identifier: GPL-3.0-only
  SPDX-FileCopyrightText: 2026 pcontacts contributors
-->

# Build & signing

## Prerequisites

- JDK 17 (Temurin recommended)
- Android SDK with platform 34
- Gradle 8.10+ (the wrapper handles this)
- For the dependency audit snapshot only (ADR-0024): a free NVD API key
  pasted into the gitignored `.env` at the repo root as
  `NVD_API_KEY=...` (request one at
  https://nvd.nist.gov/developers/request-an-api-key). CI has its own
  key in the `NVD_API_KEY` secret.

## Debug build

```bash
./gradlew :app:assembleDebug
```

The debug APK is signed with the default Android debug key and installs
directly via `adb install`.

## Release build (local)

1. Generate a keystore (once):

   ```bash
   keytool -genkeypair -v -keystore release.keystore \
     -alias pcontacts -keyalg RSA -keysize 4096 \
     -validity 10000 -storepass <password> -keypass <password>
   ```

2. Create or edit `~/.gradle/gradle.properties` (NOT committed):

   ```properties
   RELEASE_STORE_FILE=/absolute/path/to/release.keystore
   RELEASE_STORE_PASSWORD=<password>
   RELEASE_KEY_ALIAS=pcontacts
   RELEASE_KEY_PASSWORD=<password>
   ```

   Alternatively, pass as command-line flags:

   ```bash
   ./gradlew :app:assembleRelease \
     -PRELEASE_STORE_FILE="$(pwd)/release.keystore" \
     -PRELEASE_STORE_PASSWORD=<password> \
     -PRELEASE_KEY_ALIAS=pcontacts \
     -PRELEASE_KEY_PASSWORD=<password>
   ```

3. Build:

   ```bash
   ./gradlew :app:assembleRelease
   ```

4. The signed APK lands in `app/build/outputs/apk/release/`.

When no signing properties are set, `assembleRelease` still succeeds
and produces an unsigned APK. This is the expected path in CI for
reproducible-build verification (signing is a separate step).

## Release build (CI)

Tag a commit with `vX.Y.Z` and push. The `release.yml` workflow runs
in the protected `release` GitHub Environment (see below) and:

1. Waits for the environment's required reviewer to approve the run.
2. Validates the Gradle wrapper checksum, then re-runs the same gates
   as `build.yml` — every module's unit tests, detekt, lint, the
   forbidden-dependency and license checks — before any secret is
   read. A tag is not trusted to come from a green commit.
3. Decodes the release keystore from `RELEASE_KEYSTORE_BASE64` to a
   temporary file.
4. Exports `RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`,
   `RELEASE_KEY_ALIAS`, and `RELEASE_KEY_PASSWORD` as environment
   variables; nothing is passed on the command line. The signing
   config in `app/build.gradle.kts` reads Gradle properties first,
   then falls back to environment variables.
5. Runs `assembleRelease` — produces a signed APK.
6. Computes SHA-256 checksums.
7. Creates a GitHub Release with the APK and checksums attached.
8. Shreds the decoded keystore from the runner.

Every action in every workflow is pinned to a full commit SHA with the
release tag as a trailing comment; Dependabot's `github-actions`
schedule bumps the SHA and the comment together. Never replace a SHA
with a tag.

### The `release` environment (owner-side, once)

The job declares `environment: release`. Until the environment exists
GitHub creates it on first use with no protection, so set it up before
the first tag of a release:

1. Settings → Environments → New environment → `release`.
2. Required reviewers: the owner.
3. Deployment branches and tags: tags matching `v*` only.
4. Environment secrets: the four `RELEASE_*` secrets below. Then delete
   the repository-level copies, so only this gated job can read them.

### Required secrets

| Secret | Description |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | `base64 -w0 release.keystore` |
| `RELEASE_STORE_PASSWORD` | Keystore password |
| `RELEASE_KEY_ALIAS` | Key alias (e.g. `pcontacts`) |
| `RELEASE_KEY_PASSWORD` | Key password |

### Repository rulesets (applied 2026-09-22)

`master` and the `v*` tags are what F-Droid and the release workflow
trust, so GitHub refuses what CI would refuse. Two rulesets exist
(Settings → Rules → Rulesets); the repository admin role bypasses both,
so the owner's direct pushes and tags keep working:

- **Branch ruleset on `master`**: require the `build` workflow's status
  checks (unit tests, lint, the three emulator legs, release build,
  reproducible build, dependency scan) to pass before merging; block
  force pushes and deletion. The owner may bypass for the release
  bump commit, which the build workflow still verifies before a tag
  can be pushed.
- **Tag ruleset on `v*`**: only the repository owner may create or
  delete release tags.

Neither touches anything F-Droid reads: it keys off the tag existing,
the version fields in `app/build.gradle.kts`, the fastlane folder at
the tagged commit and the published release APK.

## Dependency audit snapshot (ADR-0024)

The app shows, next to its version, whether any shipped dependency has a
known CVE, and a Dependencies screen with every artifact, its license and
its CVEs. It never looks anything up at runtime: what it shows is
`app/src/main/assets/dependency-audit.json`, a committed file that
`:app:dependencyAudit` writes from the resolved release classpath, the POM
licenses, the Dependency-Check JSON report and the `<notes>` of
`config/dependency-check-suppressions.xml` (the reason shown for an
amber, "assessed" CVE).

```bash
./gradlew :app:dependencyCheckAnalyze :app:dependencyAudit   # needs NVD_API_KEY in .env
git add app/src/main/assets/dependency-audit.json
```

Without a local key, download the `dependency-check-reports` artifact of
the latest CI scan, drop its JSON at
`app/build/reports/dependency-check/dependency-check-report.json` and run
`:app:dependencyAudit` alone.

`:app:verifyDependencyAudit` keeps the file honest: the CI unit-test job
fails when the classpath and the snapshot differ (a bump without a
regenerated snapshot), and the CI scan job fails when it finds an open
CVE the snapshot does not list. A red status therefore only ships if the
owner regenerates the snapshot with an open CVE and releases anyway; the
app then posts one notification per version pointing at the screen.

### Runtime advisory check (ADR-0025)

Independent of the snapshot, the user can turn on a daily check of the
same artifact list against osv.dev under Privacy. It is off by default,
lives in `:core:advisories` behind its own host guard, and its findings
are merged into the same chip and screen; a muted advisory counts as
assessed until the artifact changes version. Nothing in the release
process changes because of it.

## Reproducible builds

ADR-0003 requires that two clean builds from the same commit produce
byte-identical unsigned APKs. This is verified in CI by the
`reproducible-build` job in `.github/workflows/build.yml`, which
assembles `:app:assembleRelease` twice with isolated Gradle caches and
compares the outputs with `diffoscope`.

### How the CI gate works

1. Two independent `assembleRelease` runs execute from the same
   checkout, each using a separate `--project-cache-dir` to prevent
   any shared state between runs.
2. `diffoscope` compares the two unsigned APKs. If it finds any
   differences, the job fails and uploads an HTML report as an
   artifact for diagnosis.
3. The job is separate from `assemble-release-r8` so a flaky
   diffoscope run does not block the canonical release-assemble.

### Local verification (without diffoscope)

```bash
# Build A
./gradlew --no-daemon --no-build-cache \
  --project-cache-dir=/tmp/repro-a/cache \
  clean :app:assembleRelease
cp app/build/outputs/apk/release/pcontacts-release-unsigned.apk /tmp/repro-a/

# Build B
./gradlew --no-daemon --no-build-cache \
  --project-cache-dir=/tmp/repro-b/cache \
  clean :app:assembleRelease
cp app/build/outputs/apk/release/pcontacts-release-unsigned.apk /tmp/repro-b/

# Compare
sha256sum /tmp/repro-a/pcontacts-release-unsigned.apk \
          /tmp/repro-b/pcontacts-release-unsigned.apk
```

If the two hashes match, the build is reproducible. If they differ,
install `diffoscope` (see below) to identify what changed.

### Installing diffoscope

**Linux (Debian/Ubuntu):**

```bash
sudo apt-get install diffoscope
```

**macOS (Homebrew):**

```bash
brew install diffoscope
```

**pip (any platform):**

```bash
pip install diffoscope
```

For full APK comparison (ZIP internals, DEX disassembly), diffoscope
benefits from having `apktool`, `enjarify` or `dex2jar`, and
`android-sdk-build-tools` available on `$PATH`. The CI job uses the
`apt` package, which pulls in most of these automatically.

### Known sources of non-determinism in Android builds

Android builds can break reproducibility through several mechanisms.
This project currently avoids all of them, but they are documented
here for diagnosis if reproducibility regresses:

1. **Timestamps in ZIP entries.** APK and JAR files are ZIP archives.
   Some build tools write the current wall-clock time into ZIP entry
   headers. AGP's `zipflinger` uses a fixed timestamp by default for
   unsigned APKs, which is why our builds are currently deterministic.

2. **File ordering in ZIP archives.** The order in which files are
   added to the APK can vary if the build tool iterates a filesystem
   directory (non-deterministic order on most filesystems). AGP
   sorts entries deterministically.

3. **R8/ProGuard mapping non-determinism.** R8 is generally
   deterministic for the same input, but different JDK patch versions
   can produce different optimisation decisions. Pin JDK version in
   CI (Temurin 17).

4. **Kotlin compiler non-determinism.** Rare, but the Kotlin compiler
   has had bugs where annotation processing order or inline function
   expansion produced different bytecode. Pinning the Kotlin version
   in `libs.versions.toml` (via the wrapper) mitigates this.

5. **Resource ordering.** AAPT2 processes resources in a
   deterministic order by default. Custom resource processors or
   generated resources that depend on filesystem iteration order can
   break this.

6. **Build tool version drift.** Different Gradle, AGP, or Kotlin
   versions produce different artifacts. The Gradle wrapper
   (`gradle-wrapper.properties`) and version catalog
   (`libs.versions.toml`) pin all three.

7. **Locale-sensitive sorting.** If any build step sorts strings
   using the JVM's default locale, results vary by machine.
   `SOURCE_DATE_EPOCH` does not fix this; the fix is to use
   locale-independent comparators. Not currently an issue.

8. **`SOURCE_DATE_EPOCH`.** Setting this environment variable forces
   compliant tools to use a fixed timestamp. Not currently needed
   (AGP already uses a fixed timestamp), but useful as a belt-and-
   suspenders measure if a new build step introduces wall-clock
   sensitivity.

## Release checklist

Follow this sequence exactly. Do not tag until the build is verified.

### 1. Prepare the version bump

- [ ] Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
      The tag will be `vX.Y.Z`; the release workflow refuses a tag whose
      version differs from `versionName`.
- [ ] Turn the `## [X.Y.Z] - Unreleased` entry in `CHANGELOG.md` into
      `## [X.Y.Z] - YYYY-MM-DD` and point its link reference at the
      release tag. `README.md`'s status line stops saying "unreleased".
- [ ] Update `README.md` status section if the release changes the
      project's maturity level (e.g. pre-release → stable).
- [ ] Update `docs/ROADMAP.md` — remove the items this release ships
      (the roadmap lists only open items; shipped work is the changelog's).
- [ ] Nothing to do for F-Droid metadata. It is **not** stored in this
      repo — see [§F-Droid](#f-droid). Once the app is published,
      F-Droid's `checkupdates` bot picks up the new signed tag on its
      own (`UpdateCheckMode: Tags` + `AutoUpdateMode: Version`).
- [ ] Create fastlane changelogs for the new `versionCode` in every
      locale directory under `fastlane/metadata/android/*/changelogs/<versionCode>.txt`
      (currently en-US, de-DE, es-ES, fr-FR, it-IT, ru-RU, zh-CN; 500 characters max each).
- [ ] If new user-facing strings were added, verify every locale's
      `strings.xml` has the same keys as the default `values/strings.xml`
      in every module.
- [ ] Regenerate the dependency audit snapshot (ADR-0024) so the release
      shows the current scan: `./gradlew :app:dependencyCheckAnalyze
      :app:dependencyAudit`, then commit `app/src/main/assets/dependency-audit.json`.
      If it reports an open CVE, bump the dependency or write an assessed
      suppression before tagging; shipping red is a deliberate decision.

### 2. Run the full test suite

```bash
./gradlew test
```

All modules must pass. Do not proceed with failures.

### 3. Build the release APK locally

```bash
./gradlew :app:assembleRelease
```

Verify the build succeeds and the APK exists at
`app/build/outputs/apk/release/pcontacts-release.apk`.

### 4. Commit, push, wait for the build workflow

```bash
git add -A
git commit -m "release: bump to vX.Y.Z"
git push
```

Wait until the `build` workflow is green on that commit — every job:
unit tests, lint, the three emulator legs, the R8 release build, the
reproducible build and the dependency scan. The release workflow
checks this and stops otherwise; it does not repeat those gates.

- [ ] Organic verification check on the test account: provoke a
      Proton captcha (Code 9001) — a VPN or Tor exit usually does —
      and confirm the in-app WebView appears, the token is stored and
      the sync resumes (ADR-0019 §Validation). Proton's captcha may
      load resources from hosts outside `proton.me`; a blocked one
      shows as an `HV: blocked` warning in the log.

### 5. Tag

Only after steps 2–4 succeed:

```bash
git tag -a vX.Y.Z -m "vX.Y.Z"
git push origin vX.Y.Z
```

Pushing the tag triggers the `release.yml` CI workflow. It checks that
the tag names `versionName` and that the tagged commit passed every
`build` job, pauses for the `release` environment's approval — approve
it in the Actions run — then re-runs the fast gates (unit tests,
detekt, lint, dependency policy), builds a signed APK, computes SHA-256
checksums, and **publishes** the GitHub Release with the APK and
checksums attached (F-Droid's `Binaries:` verification downloads that
APK, so the release is not left as a draft).

### 6. Check the GitHub Release

CI publishes the release with generated notes; review them and edit
if needed. Only if CI failed after signing, create the release
manually:

```bash
gh release create vX.Y.Z --title "vX.Y.Z" --notes "$(cat <<'EOF'
<release notes>
EOF
)"
```

### 7. Verify the published release

```bash
gh release view vX.Y.Z --json assets --jq '.assets[] | "\(.name) \(.size)"'
```

Confirm the APK and SHA256SUMS.txt are attached and the APK size is
in the expected range.

### What NOT to do

- **Do not tag before building.** The tag is the release gate. If the
  build fails after tagging, you ship a broken release.
- **Do not skip the local build.** CI builds too, but the local build
  is the verification step that catches issues before they become a
  public tag.
- **Do not bundle unrelated changes into a release commit.** The
  release commit contains only version bumps, changelog, and metadata.

## F-Droid

F-Droid builds from source using its own signing key. The `fastlane/`
metadata directory structure is expected by the F-Droid build process.

### Where the metadata lives

The build recipe (`io.pcontacts.app.yml`) is **not** in this repo. It
lives in F-Droid's own metadata repository:

- Canonical: `metadata/io.pcontacts.app.yml` in
  [fdroiddata](https://gitlab.com/fdroid/fdroiddata)
- Inclusion request:
  [fdroiddata MR !39186](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/39186),
  open since 2026-05-27

Edit it on the fork (`gitlab.com/andreabenetton/fdroiddata`, branch
`master`) — pushing there updates the MR. A copy previously lived at
`fdroid/io.pcontacts.app.yml`; it was read by nothing, drifted from the
reviewed version, and was removed. Do not reintroduce one.

Reviewer-imposed constraints already settled on that MR — re-breaking
any of these fails CI:

- `commit:` is a full hash, never a tag.
- `Builds:` holds only the latest version.
- `Binaries:` + `AllowedAPKSigningKeys:` enable reproducible-build
  verification against the GitHub release APK. `Binaries:` keeps a
  trailing space; `fdroid rewritemeta` insists on it.
- `Categories: [Contact]` and a `NonFreeNet` AntiFeature for the
  Proton dependency.

The `fdroid rewritemeta` CI job enforces byte-level formatting (~80
column wrapping, field order). It has been the single cause of most
stalls on this MR — a red `rewritemeta` reads to the maintainer as
"waiting on submitter" and the MR can sit for weeks.

Never point a build entry at a version before 1.3.4: earlier releases
strip `ezvcard.parameter` / `ezvcard.util` under R8, so contact parsing
fails silently and sync yields zero contacts.

### Listing content (icon, description, screenshots)

Only the *build recipe* lives in fdroiddata. Everything shown on the
listing page comes from `fastlane/metadata/android/<locale>/` in this
repo — `full_description.txt`, `short_description.txt`, `title.txt`,
`images/icon.png`, `images/phoneScreenshots/`. F-Droid reads these from
the source checkout it builds.

So changing the icon or the description needs no fdroiddata edit. Do
**not** copy them into `<fdroiddata>/metadata/io.pcontacts.app/`;
[F-Droid's own docs][fd-desc] say that path is for self-hosted
repositories only, and screenshots there are rejected outright.

The catch is timing: F-Droid renders the metadata from the commit the
build entry pins, so listing changes appear only once a release
containing them is built — not when they land on `master`.

[fd-desc]: https://f-droid.org/docs/All_About_Descriptions_Graphics_and_Screenshots/
