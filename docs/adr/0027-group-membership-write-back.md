<!--
  SPDX-License-Identifier: GPL-3.0-only
  SPDX-FileCopyrightText: 2026 pcontacts contributors
-->

# ADR-0027: Group membership changed on the phone is pushed to Proton

- **Status:** Accepted
- **Date:** 2026-10-07
- **Deciders:** project owner
- **Related:** ADR-0008 (Room mapping), ADR-0010 (ContactsContract write strategy), ADR-0017 (bidirectional sync; §8 deferred groups), ADR-0018 (scope expansion)

## Context

Groups synced one way: Proton contact groups (labels of type 2) became Android Groups and
every pull rebuilt a contact's GroupMembership rows from Proton. Adding a contact to a group,
or removing it, on the phone never reached Proton, and the next pull undid it. The push side
also never read GroupMembership rows while the pull's content hash included them, so a
group-only change was either skipped or re-queued as an empty update on every run.

`[V]` WebClients (commit pinned in `docs/API_RESEARCH.md`): membership lives on **ContactEmails**,
not on the contact. `useApplyGroups` sends `PUT contacts/v4/contacts/emails/label` or
`.../emails/unlabel` with `{LabelID, ContactEmailIDs}`, one label per request
(`packages/shared/lib/api/contacts.ts`); the encrypted and signed cards are not touched; the UI
offers no groups to a contact without an email. The contact's own `LabelIDs` follow its emails
(`[V]` live 2026-10-08: after labelling, the pull — which rebuilds groups from those IDs — kept
the membership).

## Decision

**Group membership changed on the phone is pushed to Proton as label deltas on the contact's
emails, against the label set Proton last reported for the contact.**

- **Per contact → per email.** Joining a group labels every email of the contact; leaving
  unlabels every email that carries the label. A label is sent only to the emails that lack it
  (or carry it), so a group deleted on Proton meanwhile sends nothing.
- **Three-way by set, without conflicts.** The base is the contact's label IDs from the last
  pull, stored sorted in `contact_map.server_label_ids` (opaque IDs, no contact content; Room
  v4). Only `local − base` is labelled and `base − local` unlabelled; what Proton changed since
  the base stays.
- **Fail closed.** No base yet (after the upgrade, until a pull fills it), no account, or the
  account's groups unreadable: nothing is pushed for groups. An empty phone group list is never
  read as "left every group" when it could not be read.
- **No email.** A contact without an email cannot join a Proton group: the change is listed as
  refused ("group needs an email") and the contact is marked for a refetch, so the phone shows
  Proton's groups again once the user discards it. The card part of the change still goes.
- **Contacts created on the phone** start from an empty base; their groups follow the create at
  once, through the same update path.
- **Pull.** The base is stored with every pulled contact. The `ModifyTime` skip also requires the
  base to match Proton's labels (`[U]` whether label calls move `ModifyTime`); a base still
  unknown after the upgrade is filled in without a fetch when the phone already shows Proton's
  groups, otherwise the contact is fetched and rewritten.
- **Editors.** `contacts.xml` declares `group_membership` in its EditSchema: `[V]` AOSP
  `ExternalAccountType` lets an account's groups be edited only when that kind is declared.
- **Out of scope:** creating, renaming and deleting groups on the phone (ADR-0017 §8 still
  holds for those); groups created on the phone have no label and are ignored.

## Alternatives considered

- **Label by contact (`PUT contacts/v4/contacts/label` with `ContactIDs`).** The web client uses
  it only in import and has no unlabel counterpart; per-email calls are what the UI does.
- **Phone set wins (push `local` without a base).** Would undo group changes made on the web
  since the last pull; the base makes both sides' changes survive.
- **Keep the base inside the sealed merge base.** Label IDs carry no contact content; a plain
  column keeps the skip check cheap and avoids unsealing per contact.

## Consequences

- Group changes from Samsung Contacts, Fossify, AOSP Contacts and any other editor reach Proton.
- Local writes that rebuild Data rows from a phone read (import, keeping the phone version of a
  conflict) keep the contact's groups instead of dropping them.
- One extra GET per pushed group change (the contact's emails and their labels), and one label
  call per changed group.
- After the upgrade, a contact whose phone groups differ from Proton's is rewritten once.

## Validation

- Unit: `ContactWriteEngineTest` (join, leave, web changes kept, unknown base, unreadable groups,
  no email, refused label, create in a group); `ContactDetailSyncEngineGroupsTest` (base stored,
  label change without `ModifyTime`, upgrade catch-up both ways, labels unreadable);
  `RawContactDataReaderTest` and the instrumented reader test; `MigrationTest` 3→4.
- Live: `LiveProtonWriteTest` labels and unlabels a canary email with an existing group (it
  skips on an account without groups; the test account is on a free plan, which has none).
- `[V]` 2026-10-08, on the owner's paid account (Pixel 9a, GrapheneOS, Android's Contacts app),
  with a probe contact and a probe group: joining pushed `groups +1 -0` and the membership
  survived the pull; leaving pushed `groups +0 -1` and stayed gone; joining after removing the
  email was refused ("group needs an email") while the email removal reached Proton, and
  Discard put Proton's state back. The label answer's exact shape stays `[U]`; it was accepted.
