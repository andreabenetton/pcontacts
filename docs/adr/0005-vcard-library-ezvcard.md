# ADR-0005: vCard library — ez-vcard, no ical.js port

- **Status:** Accepted
- **Date:** 2026-05-22
- **Deciders:** project owner

## Context

Proton's web client serializes contacts as vCard 4.0 (RFC 6350) fragments, splits them across multiple `ContactCard` objects by encryption requirements, and parses them with `ical.js`. We need a JVM-side library that:

1. Parses vCard 4.0 (and tolerates vCard 3.0, which some imported contacts may use).
2. Serializes vCard 4.0 with predictable output (canonicalization matters for signature compatibility).
3. Handles every property the §8 mapping table requires (FN, N, EMAIL, TEL, ADR, ORG, TITLE, ROLE, NOTE, BDAY, ANNIVERSARY, URL, PHOTO, NICKNAME, CATEGORIES, IMPP, UID, plus Proton's `x-pm-*` extensions).
4. Has a GPL-3.0-compatible license.
5. Is mature enough that we are not also writing a vCard parser.

Options:

- **ez-vcard** (`com.googlecode.ez-vcard:ez-vcard`). BSD-3-Clause license. Supports vCard 2.1, 3.0, 4.0. Active. Well-documented JavaDoc. Already used by DAVx5 and many CardDAV clients on Android.
- **Port ical.js** to Kotlin. Significant work (ical.js is several thousand lines of JS, includes calendar logic we don't need).
- **biweekly** (sibling project to ez-vcard). Calendars, not contacts.
- **vinnie** (older). Outdated.

## Decision

Use **ez-vcard** as the vCard 4.0 parser and serializer.

Custom Kotlin code on top of ez-vcard handles:

- The Proton card-type partitioning rules (`splitVCardProperties` equivalent — see ADR-0007's mapping).
- Merging multiple `VCard` fragments into a single `VCard` (the `mergeVCard` equivalent), discarding stray UIDs from non-SIGNED cards.
- The `x-pm-*` extension properties (`x-pm-encrypt`, `x-pm-sign`, `x-pm-mimetype`, `x-pm-scheme`, `x-pm-tls`) — ez-vcard supports custom property classes.

For signature verification, we sign/verify the **exact bytes** Proton produced/expects. We do **not** round-trip through ez-vcard before verifying signatures (round-tripping risks whitespace / line-ending drift). The decrypt pipeline verifies the raw plaintext byte stream first, then parses with ez-vcard for property extraction.

## Alternatives considered

- **Port ical.js.** Rejected — multi-week effort for no functional gain.
- **Hand-roll a vCard 4.0 parser.** Rejected — RFC 6350 has enough edge cases (line folding, quoted-printable legacy, parameter quoting, structured-value escape) that a hand-rolled parser is a maintenance liability.
- **biweekly.** Rejected — wrong project (iCalendar, not vCard).

## Consequences

- Add `com.googlecode.ez-vcard:ez-vcard:0.12.x` to `:core:proton-contacts`.
- ez-vcard's BSD license is compatible with GPL-3.0 (recorded in NOTICE).
- Signature verification operates on raw card bytes, not ez-vcard output. The parser is downstream of verification.
- For write-back (phase 9), we must produce vCard output whose canonical form is stable across releases of ez-vcard. We will pin the version and write a fixture-based serialization test that fails on any output change.
- Proton's `x-pm-*` extension properties are wrapped as custom ez-vcard `VCardProperty` subclasses in `:core:proton-contacts`.

## Validation

- Round-trip test: parse a corpus of real Proton vCards (captured from a test account, encryption removed) with ez-vcard, re-serialize, parse again. Resulting object graphs are equal.
- Signature-bytes test: given a raw signed card from a fixture, the verify path operates on the raw bytes (no ez-vcard round-trip) and succeeds; the parse path runs on the same raw bytes after verification.
- `x-pm-*` custom properties are recognized as named properties (not generic `RawProperty`) — covered by unit test.
