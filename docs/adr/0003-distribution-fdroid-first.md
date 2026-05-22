# ADR-0003: Distribution — F-Droid first, sideload-friendly

- **Status:** Accepted
- **Date:** 2026-05-22
- **Deciders:** project owner
- **Related:** ADR-0001, ADR-0015

## Context

The app needs a distribution channel that:

1. Matches its GPL-3.0 nature and audit-friendly stance.
2. Tolerates a permissions footprint (`READ_CONTACTS`, `WRITE_CONTACTS`, `AUTHENTICATE_ACCOUNTS`, `WRITE_SYNC_SETTINGS`, custom account type) that Google Play reviews scrutinize.
3. Reaches a user base that already trusts non-Play distribution (Proton's privacy-conscious audience overlaps strongly with F-Droid's).
4. Supports reproducible builds for verifiability.

Google Play imposes constraints that conflict with several other ADRs: data-safety disclosures that effectively require non-trivial telemetry guardrails, Play Integrity attestation pressure, ongoing target-SDK bumps, and a legal review process that may reject "Proton" in the title due to trademark friction.

F-Droid, conversely, expects:

- No proprietary dependencies (rules out Google Play Services, Firebase, etc. — see ADR-0015).
- No "anti-features" (no embedded interpreters, no closed-source blobs, no telemetry by default).
- A reproducible build that F-Droid's build server can reproduce byte-for-byte.

Sideload-via-GitHub-Releases is the universal backup channel for users who can't or won't use F-Droid.

## Decision

Primary distribution: **F-Droid main repository**, once the app meets inclusion criteria. Builds and tags are reproducible.

Secondary distribution: **GitHub Releases** with signed APKs and SHA-256 + GPG-signed checksums for sideload, including users who prefer [Obtainium](https://github.com/ImranR98/Obtainium).

**Out of scope for v1: Google Play.** Not blocked forever, but not pursued.

## Alternatives considered

- **Google Play only.** Rejected: incompatible with the project's privacy stance, the Play review surface for contacts/accounts is high, and the trademark-name concern with "Proton" is real.
- **Both F-Droid and Play via product flavors.** Rejected as needless QA overhead at v1. Re-evaluate post-MVP.
- **Sideload only.** Rejected as primary path: zero auto-update, low reach. Keep as a secondary path.
- **Custom F-Droid repository (IzzyOnDroid-style).** Considered — useful as a transitional channel while F-Droid main inclusion is in flight. We will publish to F-Droid main but accept that an IzzyOnDroid mirror may exist for faster availability.

## Consequences

- Build is reproducible from a clean checkout (verified in CI via two-run `diffoscope`).
- No Google Play Services dependency. No FCM, no Play Integrity, no Maps. (ADR-0015 enforces this at the dependency graph level.)
- `fastlane/metadata/android/en-US/` ships with the repo for F-Droid's auto-discovery.
- The app name and store description do not lead with "Proton" or use Proton trademarks — the directory and app id is `pcontacts`.
- Release process is `git tag -s vX.Y.Z` → CI builds → publishes APK + checksums to GitHub Release → F-Droid build server picks it up.
- Users on devices without F-Droid get an `.apk` and a deterministic checksum; auto-update via Obtainium or manual.

## Validation

- F-Droid lint passes on `assembleRelease` (using `fdroidserver` container in CI).
- Two CI runs from clean checkouts produce identical APK bytes (`diffoscope` reports no differences).
- Initial inclusion request to F-Droid is accepted, OR a documented reason for any anti-feature flag exists in this repo.
