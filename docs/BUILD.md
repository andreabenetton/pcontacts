<!--
  SPDX-License-Identifier: GPL-3.0-only
  SPDX-FileCopyrightText: 2026 pcontacts contributors
-->

# Build & signing

## Prerequisites

- JDK 17 (Temurin recommended)
- Android SDK with platform 34
- Gradle 8.10+ (the wrapper handles this)

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

Tag a commit with `vX.Y.Z` and push. The `release.yml` workflow:

1. Decodes the release keystore from `RELEASE_KEYSTORE_BASE64` to a
   temporary file.
2. Exports `RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`,
   `RELEASE_KEY_ALIAS`, and `RELEASE_KEY_PASSWORD` as environment
   variables. The signing config in `app/build.gradle.kts` reads
   Gradle properties first, then falls back to environment variables.
3. Runs `assembleRelease` — produces a signed APK.
4. Computes SHA-256 checksums.
5. Creates a GitHub Release with the APK and checksums attached.
6. Shreds the decoded keystore from the runner.

### Required secrets

| Secret | Description |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | `base64 -w0 release.keystore` |
| `RELEASE_STORE_PASSWORD` | Keystore password |
| `RELEASE_KEY_ALIAS` | Key alias (e.g. `pcontacts`) |
| `RELEASE_KEY_PASSWORD` | Key password |

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
cp app/build/outputs/apk/release/app-release-unsigned.apk /tmp/repro-a/

# Build B
./gradlew --no-daemon --no-build-cache \
  --project-cache-dir=/tmp/repro-b/cache \
  clean :app:assembleRelease
cp app/build/outputs/apk/release/app-release-unsigned.apk /tmp/repro-b/

# Compare
sha256sum /tmp/repro-a/app-release-unsigned.apk \
          /tmp/repro-b/app-release-unsigned.apk
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
- [ ] Add a `## [X.Y.Z] - YYYY-MM-DD` entry in `CHANGELOG.md` with a
      link reference at the bottom.
- [ ] Update `README.md` status section if the release changes the
      project's maturity level (e.g. pre-release → stable).
- [ ] Update `docs/ROADMAP.md` — check off completed items, update the
      "Done" heading version if needed.
- [ ] Update `CurrentVersion` and `CurrentVersionCode` in
      `fdroid/io.pcontacts.app.yml`.
- [ ] Create fastlane changelogs for the new `versionCode` in all
      locales: `fastlane/metadata/android/{en-US,it-IT,de-DE}/changelogs/<versionCode>.txt`.
- [ ] If new user-facing strings were added, verify Italian and German
      translations have the same keys as the default `values/strings.xml`
      in every module.

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

### 4. Commit and push

```bash
git add -A
git commit -m "release: bump to vX.Y.Z"
git push
```

### 5. Tag

Only after steps 2–4 succeed:

```bash
git tag -a vX.Y.Z -m "vX.Y.Z"
git push origin vX.Y.Z
```

Pushing the tag triggers the `release.yml` CI workflow, which builds a
signed APK, computes SHA-256 checksums, and creates a draft GitHub
Release with the APK and checksums attached.

### 6. Create or finalise the GitHub Release

If CI created a draft release, review and publish it. Otherwise create
it manually:

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
