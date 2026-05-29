# ADR-0021: "Send via Proton Mail" custom data row + intent contract

- **Status:** Accepted
- **Date:** 2026-05-29
- **Deciders:** pcontacts maintainers
- **Related:** ADR-0008 (content-hash idempotency), ADR-0010
  (ContactsContract write strategy), Plan §22 Stage B

## Context

Phase 11 (ADR-0020) shipped the address-key decrypt fan-out and the
first end-to-end sync against a real Proton account. In the system
Contacts app, the resulting `azuresky` RawContact correctly
aggregated with the user's local WhatsApp / Telegram contacts —
but where WhatsApp and Telegram show prominent per-source action
chips ("Message via WhatsApp", "Telegram voice"), the pcontacts
row showed only the account-source header with no actions
specific to Proton.

WhatsApp and Telegram achieve their chips by writing custom
ContactsContract.Data rows with non-standard `MIMETYPE` values
alongside the standard Email/Phone rows. Each Contacts app reads
those rows and renders them as chips, dispatching `ACTION_VIEW`
on the row's Data URI to the activity registered for the MIMETYPE.

For pcontacts, the chip's value question — "what unique pcontacts
action should the chip surface?" — was unsettled. Candidate
actions included: edit-on-web (`mail.proton.me/contacts/<id>`),
encryption-verification panel, force-resync, and
"Send via Proton Mail". Per a follow-up planning conversation,
the **routed compose** is the only one that delivers value the
existing Email row doesn't already provide:

- Android's default `mailto:` chooser may route compose to Gmail
  or another mail client. The chip's explicit-package routing
  guarantees Proton Mail handles outbound mail to a Proton
  contact, matching the user's privacy intent of staying inside
  Proton.
- Cellular call / SMS chips would only duplicate the standard
  Phone-row actions Contacts apps already render. Skipped.
- "Edit on web" duplicates a plain browser bookmark. Skipped for
  MVP; revisit when the web compose URL question reopens.
- Encryption-verification surfacing is interesting but is a
  larger feature (new screen, decrypt-state cache, intent
  contract). Tracked separately, not in this ADR.

## Decision

For every email address on a Proton contact, the writer emits
one custom Data row with:

- `MIMETYPE = "vnd.android.cursor.item/vnd.io.pcontacts.send_via_proton_mail"`
- `DATA1 = <email address>` (the address the activity composes to)
- `DATA2 = "Send via Proton Mail"` (self-describing row summary;
  Contacts apps prefer the activity's `android:label` for the
  rendered chip text)

The MIMETYPE is owned by `PContactsMimeTypes.SEND_VIA_PROTON_MAIL`
in `:core:contacts-writer`. The string is a wire-format constant:
renaming it orphans every row already persisted on device.
Deprecation must add a new MIMETYPE rather than mutate the
existing one.

A single thin intent-router Activity in `:app`,
`SendViaProtonMailActivity` (NoDisplay + noHistory), is registered
in the manifest with an `<intent-filter>` matching the MIMETYPE.
On dispatch it:

1. Reads `DATA1` from the row's Data URI.
2. Tries `Intent(ACTION_SENDTO, mailto:<email>).setPackage("ch.protonmail.android")`.
3. On `ActivityNotFoundException`, falls back to
   `Intent(ACTION_VIEW, "https://mail.proton.me/u/0/inbox#compose=true&to=<email>")`
   [U] — the fragment-style compose deeplink is the current
   mail.proton.me convention; if it changes, the second-tier
   fallback opens the bare `https://mail.proton.me` inbox so the
   action is never silently broken.

Hash bump: `EmailSyncHash` prepends a `FORMAT_VERSION = "v2"`
constant to its payload so already-synced contacts (hashed under
v1, no chip rows) mismatch on the next sync run and rewrite once.
One-shot cost (~12 minutes for the 1143-contact test account;
matches the Phase 11 first-sync wall time). Subsequent runs
return to the modify-time / hash skip path.

`xml/contacts.xml` (the `CONTACTS_STRUCTURE` meta-data) gains a
`ContactsDataKind` entry mapping the MIMETYPE to the chip icon
+ summary/detail columns, so AOSP Contacts apps (which read this
file directly) render the chip without needing to discover the
intent-filter.

## Alternatives considered

- **No chip at all.** Keep the per-source row stick-figure
  fallback. Rejected: leaves the pcontacts row visually mute
  next to WhatsApp/Telegram and surfaces no Proton-specific
  affordance.
- **Generic "Email via Proton Mail" account-level chip.** A
  single chip at the account-header level rather than one per
  email. Rejected: doesn't scale when a contact has multiple
  emails (which is most contacts in practice).
- **Call / SMS chips.** Per-phone chips for "Call" / "SMS".
  Rejected: duplicates the standard Phone-row actions that
  every Contacts app already renders, adding visual noise
  without unique value.
- **`mailto:` via implicit chooser.** Drop the explicit-package
  targeting and let Android's chooser pick. Rejected: defeats
  the chip's only differentiator (guaranteed routing to Proton
  Mail).
- **Custom action string** (e.g.
  `io.pcontacts.intent.action.COMPOSE_PROTON_MAIL`). Rejected:
  Contacts apps dispatch `ACTION_VIEW` on the Data URI by
  convention; using a non-standard action requires every
  Contacts app to special-case our MIMETYPE.

## Consequences

Easier:
- Visual parity with WhatsApp/Telegram per-source chips.
- Proton-routed compose without a launcher-app round-trip.
- Web fallback keeps the chip useful for users who haven't
  installed Proton Mail Android.

Harder / new obligations:
- One-shot ~12-min rewrite on the first sync after rollout (hash
  bump). Operational signal: the next sync after install will
  re-write every contact even if nothing changed server-side.
  Acceptable per Plan §18 (hash-format-bump cost is one-shot
  and called out in the commit message).
- Per-contact op count grows from `1 RawContacts + 1 SN + N Email
  + …` to `1 RawContacts + 1 SN + 2N Email-related rows + …`.
  Materially: ContactsProvider stores ~1100 extra rows on the
  1143-contact test account. Provider-side cost negligible.
- New MIMETYPE is a stability promise (see Decision). A rename
  orphans every existing row.
- Web fallback URL `[U]` — the
  `https://mail.proton.me/u/0/inbox#compose=true&to=` pattern is
  inferred from current web client behavior. If Proton renames
  the deeplink format, the chip silently falls through to the
  bare inbox URL (still functional, less ergonomic). Follow-up:
  validate against the live web client on each release cycle.
- Activity adds 1 declared component to the manifest; intent
  surface area grows accordingly. `exported="true"` is required
  for Contacts apps to dispatch; the intent-filter scopes the
  exposure to the custom MIMETYPE only.

## Validation

- `:core:contacts-writer:test` — `ContactsContractOpsTest`
  asserts the chip Data row is emitted per email on Create and
  re-emitted under delete-and-reinsert on Update. New negative
  test confirms email-less contacts emit zero chips.
- `:core:sync:test` — existing `ContactWriteEngineTest` paths
  consume `EmailSyncHash.compute(...)` end-to-end; the
  FORMAT_VERSION bump validates implicitly via the deterministic
  same-input-same-hash behavior.
- Manual on-device: install on Pixel 9a, trigger a sync, wait
  for the one-shot rewrite to complete, open a Proton contact
  in Fossify Contacts; the source-list section should show one
  "Send via Proton Mail" chip per email address with the
  Proton-purple envelope icon. Tap → Proton Mail Android
  compose (if installed) or the Proton web compose URL.
- Regression watch: if a future Proton Mail Android release
  stops accepting `ACTION_SENDTO mailto:` with `setPackage`,
  the explicit-package call throws `ActivityNotFoundException`
  and the activity falls through to the web compose URL —
  graceful degradation, no crash.
