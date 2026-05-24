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

2. Build and sign:

   ```bash
   ./gradlew :app:assembleRelease \
     -Pandroid.injected.signing.store.file="$(pwd)/release.keystore" \
     -Pandroid.injected.signing.store.password=<password> \
     -Pandroid.injected.signing.key.alias=pcontacts \
     -Pandroid.injected.signing.key.password=<password>
   ```

3. The signed APK lands in `app/build/outputs/apk/release/`.

## Release build (CI)

Tag a commit with `vX.Y.Z` and push. The `release.yml` workflow:

1. Decodes the release keystore from `RELEASE_KEYSTORE_BASE64`.
2. Signs the APK using `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`,
   `RELEASE_KEY_PASSWORD`.
3. Computes SHA-256 checksums.
4. Creates a GitHub Release with the APK and checksums attached.
5. Shreds the decoded keystore from the runner.

### Required secrets

| Secret | Description |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | `base64 -w0 release.keystore` |
| `RELEASE_STORE_PASSWORD` | Keystore password |
| `RELEASE_KEY_ALIAS` | Key alias (e.g. `pcontacts`) |
| `RELEASE_KEY_PASSWORD` | Key password |

## Reproducible-build verification

Reproducible-build CI gate (diffoscope comparison of two clean builds)
is a tracked follow-up for the 1.0 release.

## F-Droid

F-Droid builds from source using its own signing key. The `fastlane/`
metadata directory structure is expected by the F-Droid build process.
