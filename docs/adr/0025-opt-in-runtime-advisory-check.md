<!--
  SPDX-License-Identifier: GPL-3.0-only
  SPDX-FileCopyrightText: 2026 pcontacts contributors
-->

# ADR-0025: Opt-in runtime advisory check of the shipped artifacts against osv.dev

- **Status:** Accepted
- **Date:** 2026-09-22
- **Deciders:** project owner
- **Related:** ADR-0024 (build-time audit snapshot), ADR-0015 (no telemetry, no Google services — this ADR carves out one opt-in network path from its host rule), ADR-0011 (module boundaries)

## Context

ADR-0024 ships a build-time audit snapshot: the app shows the CVE state of
its dependencies as of the scan that preceded the release. The owner wants
the state to move between releases as well: a vulnerability published
after the release should be visible on the device without waiting for the
next version.

That needs a network request to a vulnerability database. ADR-0015 allows
network traffic only to `*.proton.me`, forbids remote configuration and
promises no telemetry. The request in question carries nothing private:
the exact list of shipped artifacts and versions is public in this
repository. What the request does reveal is the device's IP address and the time
of the request; the artifact list itself lets the operator infer which
app is asking. The owner frames this as a trade-off between privacy and
security and leaves the decision to the user.

## Decision

**The app can check its shipped artifacts against osv.dev at runtime, and
only when the user has switched the check on. The switch is off by
default, lives in the Privacy section, and states in plain words what is
sent and to whom. When on, the app sends the coordinates of exactly the
artifacts in the ADR-0024 snapshot to `api.osv.dev` (batch query by Maven
package and version), at most once every 24 hours in the background plus
on an explicit "Check now", fetches details only for advisories the
snapshot does not already list, and merges them into the same dot and
Dependencies screen as open vulnerabilities. A new advisory is announced
once by a notification.**

Constraints that keep this the narrow exception it is:

- **Nothing else depends on the answer.** The result changes a status and
  a list; it never enables, disables or alters any behaviour of the app.
  There is no remote configuration and no kill switch.
- **One host, guarded mechanically.** The request goes through its own
  HTTP client in a new pure-JVM module `:core:advisories`, whose DNS guard
  resolves `api.osv.dev` only (and localhost for tests), mirroring the
  Proton client's guard. The Proton client is untouched and still refuses
  every non-Proton host. The CLAUDE.md rule "OkHttpClient is constructed
  only in `:core:proton-api`" becomes "only in `:core:proton-api` and
  `:core:advisories`, each behind its own host guard".
- **Only public data leaves the device.** The request body is the artifact
  list; no account, contact, device or session information is attached,
  and the client sends no cookies and no custom headers beyond content
  type.
- **Off means off.** With the switch off, the module is never invoked and
  the periodic work is cancelled; switching off also drops the cached
  result. A fresh install makes no request.
- **The result is cached, not trusted for anything but display.** The
  cached list, the last-check time and the set of already-announced ids
  are ordinary preferences (public data, ADR-0009 does not apply).
- **Snapshot first.** An advisory whose id, or any of whose aliases, the
  snapshot already lists (open or assessed) is not "new"; the snapshot's
  assessment stands. OSV's Maven data is matched by package and version,
  so the CPE false positives of the build-time scanner do not recur here
  `[A]` (to be observed on the test device).

## Alternatives considered

- **Keep the check build-time only (ADR-0024 alone).** Rejected by the
  owner: the delay until the next release is too long for a status that
  is meant to inform.
- **NVD at runtime.** Rejected: needs an API key per caller, and a key in
  a public APK is public; also a heavier, CPE-based matching that produces
  the false positives ADR-0024 has to suppress.
- **Fetch an advisory file from the project's own release channel.**
  Rejected: same network cost, and it makes the project a server whose
  content changes app state; osv.dev is at least a neutral, public
  database.
- **On by default.** Rejected: ADR-0015's promise is that a fresh install
  talks to Proton only; the user must choose otherwise.

## Consequences

- ADR-0015's host rule now has one opt-in exception; its enforcement text
  and CLAUDE.md are updated to say so. The threat model gains a row for
  the metadata this request reveals.
- Google runs osv.dev. A user who turns the switch on accepts that Google
  sees their address once a day and can infer the app from the artifact
  list; the switch text says so and names the trade-off. This is the
  whole reason the default is off.
- With the switch off the app shows no green, amber or red at all: the
  chip next to the version is a plain link to the dependency list, and
  the list carries no colours and no mute control. Colours are a claim
  about the present, which only the runtime check can make.
- The status dot can now turn red between releases. The Dependencies
  screen marks such entries as runtime advisories with their osv.dev link
  and shows when the last check ran.
- No new dependency: the module uses the OkHttp and kotlinx-serialization
  already shipped for the Proton client.

## Validation

- Unit tests with MockWebServer for the request shape, the response
  parsing and the host guard; pure tests for the "new versus known"
  comparison and the cache codec.
- On the test device: switch on, "Check now", request observed to reach
  `api.osv.dev` only; switch off, no request on the next day.
- The ADR-0015 network gate stays as it is for the Proton client.
