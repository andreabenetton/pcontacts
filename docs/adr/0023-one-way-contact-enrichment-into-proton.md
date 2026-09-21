<!--
  SPDX-License-Identifier: GPL-3.0-only
  SPDX-FileCopyrightText: 2026 pcontacts contributors
-->

# ADR-0023: One-way contact enrichment — pull linked accounts' fields into Proton

- **Status:** Accepted
- **Date:** 2026-09-21
- **Deciders:** project owner
- **Related:** ADR-0007 (decrypt/read client-side only), ADR-0010 (ContactsContract write strategy), ADR-0011 (module boundaries), ADR-0017 (bidirectional sync), ADR-0022 (ContactsProvider authoritative)

## Context

Android aggregates RawContacts from different accounts into one displayed
Contact by matching name/data, but aggregation is **display-only**: each
RawContact stays owned by its account. A field that lives in the WhatsApp
or local copy is not in the Proton copy, so it never syncs to Proton and
is not durable there.

Concrete field report — "Evelino Belloli", one aggregated `contact_id`
across four RawContacts: Proton (email only), local/Telefono (phone),
WhatsApp (phone + in-app call rows), Telegram (in-app call/profile rows).
The user wants the phone number to live in — and sync to — Proton.

Until now pcontacts reads only its own account (`io.pcontacts.account`)
and has no knowledge of sibling RawContacts. Bringing sibling data into
Proton requires reading across the aggregation cluster, which crosses the
read boundary ADR-0007 draws around our own account.

## Decision

**On explicit, per-action user consent, pcontacts MAY read the standard
PIM fields of a contact's sibling RawContacts (other accounts) that Android
has aggregated under the same Contact, and write user-selected fields that
are missing from the Proton copy into the Proton RawContact, which then
syncs to Proton. The flow is strictly one-way into Proton and read-only
toward every other account.**

pcontacts never writes to, edits, deletes, or re-aggregates a
non-pcontacts RawContact; never mirrors Proton data back out to another
account; and never transmits or persists sibling data anywhere except as
the specific fields the user chose to import into their own Proton contact.

Concretely, per enrichment action:

1. **Discover the cluster** via `RawContacts.CONTACT_ID` at action time.
   Membership is never persisted — Android re-aggregates freely (ADR-0022).
2. **Read sibling Data rows**, restricted to an **allowlist** of standard
   PIM mimetypes (StructuredName, Phone, Email, StructuredPostal,
   Organization, Note, Im, Nickname, Event, Website). Everything else —
   app-specific/action rows (`vnd.com.whatsapp.*`, `vnd.org.telegram.*`),
   our own `send_via_proton_mail` chip, GroupMembership — is skipped
   (fail-closed: under-import rather than pull junk).
3. **Diff against the Proton copy** and offer only fields that are *absent*
   from it, normalized so existing values are not re-offered (phone →
   E.164, email → lowercased).
4. **The user selects** what to import; nothing is written without a choice.
5. **Write** the selected fields onto the Proton RawContact via
   `:core:contacts-writer` (`caller_is_syncadapter=true`, ADR-0010), mark it
   dirty → the outbox syncs it to Proton as an UPDATE (reuses the write path
   and `ThreeWayMerger` set-merge). Additive only — never overwrite an
   existing Proton field.

### Amendment (2026-09-21): creating the Proton copy

When the aggregate holds **no** Proton RawContact, the same action may
create one instead of stopping: a new RawContact under the pcontacts
account carrying the name and the user-selected fields from the linked
rows, pinned into the same aggregate (`AggregationExceptions`
KEEP_TOGETHER) so Android does not split it off. It is written with no
`SOURCE_ID` and `DIRTY=1`, so the outbox treats it as a CREATE and
stamps the server id afterwards — the ordinary create path, no special
sync handling. The selection must include at least one phone, email,
address or IM account (`ContactRow`'s guard). Everything else above
holds unchanged: one-way, consent per action, the linked rows are never
modified.

## Alternatives considered

- **Manual edit in the system Contacts app** — the only option today; works, but is per-field, error-prone (easy to save to the wrong account), and has no dedup or bulk. Kept as the fallback, not sufficient as the answer.
- **Merge-all-into-one-account** (some third-party apps) — rewrites every raw into one account; destructive to the other accounts' data and not field-selective. Rejected — violates one-way/read-only.
- **Two-way cluster sync** (mirror Proton edits back to WhatsApp/local) — writes into apps we do not own, multiplies risk. Rejected; explicitly out of scope.
- **Automatic enrichment without consent** — silent cross-account reads break the privacy stance. Rejected; every sibling read is gated behind an explicit action.
- **Persisting cluster membership** — aggregation is recomputed by Android; stale membership would import into the wrong contact. Rejected; compute at action time.

## Consequences

- First time pcontacts reads outside its own account, widening the ADR-0007 boundary. New standing obligation: every sibling read is gated behind explicit per-action consent; sibling data is never persisted or transmitted; the only durable output is the user-chosen fields on their own Proton contact.
- Requires maintaining the PIM mimetype **allowlist** as new types appear; unknown types are skipped, so the failure mode is missing an importable field, never leaking an app-specific/action row.
- Adds a UI surface (per-contact "Import details" checklist) and a cluster reader in `:core:contacts-writer` (ContactsContract-only, no crypto); `:feature`/`:app` reach it within the ADR-0011 boundaries.
- No new network endpoints, no new permissions (`READ_CONTACTS` is already held), and no decrypted Proton data leaves the device.
- One-way + read-only toward other accounts bounds the blast radius: pcontacts cannot corrupt WhatsApp/Telegram/local data.
- The write marks the Proton row dirty and syncs through the normal outbox — no special sync path; interacts cleanly with ADR-0022 self-healing.

## Validation

- **Instrumented test** (`:core:contacts-writer`): a cluster of RawContacts across accounts; import selected fields into the Proton raw; assert only selected-and-missing fields were written, *no* writes touched any sibling raw, and the Proton row is marked dirty.
- **Unit tests**: allowlist filtering (WhatsApp/Telegram action rows and the Proton chip excluded); normalization/dedup (a phone already on the Proton copy is not re-offered).
- **Manual** (Evelino Belloli): import the phone from the WhatsApp/local copy → it appears on the Proton contact and syncs up, while the WhatsApp/local/Telegram copies are unchanged.
- **Privacy audit**: confirm no code path transmits sibling data off-device or persists it beyond the fields the user imported.
