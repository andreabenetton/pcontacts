<!--
  SPDX-License-Identifier: GPL-3.0-only
  SPDX-FileCopyrightText: 2026 pcontacts contributors
-->

# ADR-0026: Move orphan "PHONE" contacts into the Proton account

- **Status:** Accepted (amended 2026-09-24 — the import list labels movable contacts; the bulk import moves the lossless ones)
- **Date:** 2026-09-24
- **Deciders:** project owner
- **Related:** ADR-0010 (ContactsContract write strategy), ADR-0017 (bidirectional sync), ADR-0022 (ContactsProvider authoritative), ADR-0023 (one-way enrichment — narrowed here)

## Context

Some apps (WhatsApp's "add contact" was seen doing it on a Pixel) save a
contact under the account `PHONE`/`PHONE`, which no authenticator on the
phone registers. Android's `ContactsProvider2.updateAccountsInBackground`
hard-deletes the raw contacts of accounts that are neither registered nor
the device's local account on every `LOGIN_ACCOUNTS_CHANGED` — adding or
removing any account, pcontacts' sign-out included. `[V]` Seen on both
test phones on 2026-09-22: such a contact vanished at the next account
change, with no tombstone.

ADR-0023 lets the user *copy* such a contact's fields into a new Proton
contact, and forbids pcontacts to write any non-pcontacts RawContact. A
copy survives, but the original row — and with it Android's state on
that row (favourite star, custom ringtone, aggregation with the person's
other copies) — is still purged later, and until then the person shows
twice as sources.

## Decision

**On explicit per-contact confirmation, pcontacts may move a RawContact
of the account `PHONE`/`PHONE` into its own account, when the aggregate
Contact has no Proton copy. This is the one exception to ADR-0023's
"never write another account's rows".**

- **Scope, exactly.** Account type `PHONE` *and* name `PHONE`. Not "any
  account without an authenticator": on Samsung (Android 11) the device's
  own local storage `vnd.sec.contact.phone` has no authenticator either and
  is kept, not purged. Other orphan accounts are added one at a time, each
  confirmed on a real phone first.
- **Only without a Proton copy.** An aggregate that already has one keeps
  the ADR-0023 import; the orphan is left for Android to purge.
- **Consent.** The import dialog offers "Move to Proton" beside the copy;
  nothing moves without that tap. The bulk import copies, never moves.
- **Mechanism.** One provider update through the sync-adapter URI decorated
  with the *source* account, selected by `_ID` and account: `[V]` AOSP
  `ContactsProvider2` appends a URI's account to a raw-contacts update's
  selection (`appendAccountIdToSelection`), so the provider itself refuses
  to touch a row outside `PHONE`/`PHONE`; `[V]` `updateRawContact` moves a
  row to the new account when `ACCOUNT_TYPE` / `ACCOUNT_NAME` change,
  keeping `_ID`, Data rows and the Contact's state. The update sets our
  account, clears `SOURCE_ID` and `SYNC1`–`SYNC4`, and sets `DIRTY=1`: the
  outbox then creates the contact on Proton and stamps its id back — the
  ordinary create path, no special sync handling. `[V]` Validated on the
  Samsung A40 (Android 11) on 2026-09-24 with an equivalent shell update;
  `[A]` the sync-adapter form on both test phones, before release.
- **What does not come along.** The first pull after the create rewrites
  the Data rows (ADR-0010), so only what the model carries survives.
  Before a move the dialog names the standard kinds that would be lost —
  events other than birthday and anniversary, relations, SIP addresses —
  and the user can copy instead. App-specific rows (WhatsApp / Telegram
  actions) and memberships in the orphan account's groups are dropped
  without asking: the apps keep their own copies, and the groups die with
  the account.

## Alternatives considered

- **Copy only (ADR-0023 as is).** Loses the row's Android state at the
  purge and leaves a doomed duplicate until then. Kept as the choice
  beside the move.
- **Move in bulk.** A list selection is a weaker consent than a per-contact
  review that names what would be lost. Rejected for now.
- **Move any orphan account.** Needs a reliable "is this the local
  account" test; Android 14+ has one (`RawContacts.getLocalAccountType`),
  Android 11 does not. Rejected until each account is confirmed.

## Consequences

- pcontacts writes another account's row, once, in a narrowly guarded
  way; ADR-0023 is narrowed accordingly and says so.
- The moved contact keeps its star, ringtone and links, and appears once.
- No new permission (`WRITE_CONTACTS` is held), no new endpoint.

## Validation

- Unit tests: the candidates offer a move only for `PHONE`/`PHONE` without a
  Proton copy; the move is a single update on the decorated source-account
  URI with the values above; the uncarried kinds are listed.
- Manual (Samsung and Pixel): a `PHONE` probe is moved, keeps `_ID`,
  `contact_id` and star, is created on Proton at the next sync, and the
  dialog named what would not come along.

## Amendment (2026-09-24): the list says it, the bulk import does it

The first live test showed the gap in "the bulk import copies, never
moves": the owner ticked a doomed contact and pressed Import, and got a
copy — the list had not said the contact could be moved, nor that the
two routes differ. The copy is exactly the doomed duplicate this ADR
exists to avoid.

- **The list labels it.** A contact this ADR can move carries a warning
  badge in the import list — Android will delete it; it moves to
  Proton — instead of "Not in Proton"; the Import button counts the
  moves ("Import 5 contacts (2 moved)").
- **The bulk import moves it**, when the move loses nothing: the label
  has said what a tick does, so the tick is the consent.
- **A lossy move still needs the review.** A contact with a detail
  Proton does not keep (other dates, relations, SIP addresses) is
  labelled "open to review" and is copied by the bulk import, never
  moved; only the per-contact dialog, which names the loss, moves it.

Scope is unchanged: exactly `PHONE`/`PHONE`, only without a Proton copy.

