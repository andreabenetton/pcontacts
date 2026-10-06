<!--
  SPDX-License-Identifier: GPL-3.0-only
  SPDX-FileCopyrightText: 2026 pcontacts contributors
-->

# Samsung (One UI)

Tested on a Galaxy A40 (SM-A405FN), Android 11, One UI, signed in to the
test account. Samsung replaces AOSP's contacts provider with its own,
which adds a recycle bin, and keeps the device's own contacts under a
local account of its own type.

## Issue 1 — a contact deleted in Samsung Contacts came back

Samsung Contacts moves a deleted contact into its Recycle bin (called
"Trash" in the One UI version on the A40). The
provider marks the row with extra `raw_contacts` columns
(`sec_in_trash`, `sec_trash_timestamp`, `sec_trash_caller_package`) and
hides it from every query, the sync adapter's included, unless the row
also carries `DELETED=1`, which it does not for our account. The sync
therefore saw neither the row nor a tombstone.

Observed on 2026-09-24, first with a shell delete and then with the
Samsung Contacts app: the contact vanished, the next sync found
`0 dirty contacts`, and ADR-0022's self-healing recreated it from
Proton. The user's deletion never reached Proton.

**Fix (cross-device, shipped in 2.1.0; ADR-0022 amendment 2026-09-24):**
a provider has a recycle bin when its `raw_contacts` cursor carries the
`sec_in_trash` column. The check reads column names only, never a
trashed row, and is not a manufacturer check: any provider with that
column gets the same treatment. On such a provider a synced contact
whose row disappears is not recreated; pcontacts asks instead
("This contact is no longer on this phone"):

- **Delete from Proton** — an ordinary queued deletion, with the
  one-hour grace period and its cancel.
- **Put it back** — the next sync recreates the contact on the phone.
- Restoring the contact from the Recycle bin while the question is open
  settles it too; the contact syncs as before.

The user-facing explanation is in the FAQ:
[I deleted a contact on a Samsung and pcontacts asks what to do](../FAQ.md#i-deleted-a-contact-on-a-samsung-and-pcontacts-asks-what-to-do).

**With the Trash switched off** (Samsung Contacts → Settings → Trash),
a deletion leaves an ordinary tombstone and nothing is asked. Observed on
2026-10-06: switching the Trash off first permanently deletes whatever
the Trash holds (Samsung asks before doing it). A synced contact deleted
in Samsung Contacts then stayed visible to the sync as `DELETED=1`,
`DIRTY=1`, with `sec_in_trash=3` (Samsung's "kept for sync" state); the
next sync queued its deletion, the app listed it under "scheduled for
deletion" with Cancel, and the pull left the contact alone while the
deletion was pending. After the grace hour the next sync deleted it on
Proton (`deleted=1`, the server went from 5 contacts to 4) and the
provider purged the tombstone.

## Issue 2 — which "phone" contacts Android deletes

Android's contacts provider hard-deletes the raw contacts of any account
that is neither registered with AccountManager nor the device's local
account, every time the list of accounts changes (pcontacts' sign-out
included). Samsung's local account is `vnd.sec.contact.phone`; it has no
authenticator on Android 11 either, but the provider treats it as local
and keeps it. A row under the bare account `PHONE`/`PHONE`, as some apps
write, is deleted: a probe with that account vanished the instant the
test account signed out (2026-09-22).

This is why ADR-0026's **Move to Proton** applies only to the exact
account `PHONE`/`PHONE`, not to "any account without an authenticator":
the looser rule would have offered to move Samsung's own local contacts.
The move was validated on this phone on 2026-09-24 with the equivalent
shell update: `_ID`, `contact_id`, the star and the Data rows survived,
and the next sync created the contact on Proton without a duplicate.

## Issue 3 — Proton missing from "Save contact to"

Samsung Contacts picks the storage of a new contact at the top of the
new-contact screen ("Save contact to"); its settings have no default
storage option. Up to 2.2.1 the list offered Phone, SIM and Google, but
not the Proton account, so a contact could not be created in Proton from
Samsung Contacts. Editing an existing Proton contact did work: on
2026-10-06 an edit saved in Samsung Contacts landed in the pcontacts row
and was pushed to Proton.

`[V]` AOSP Contacts counts a third-party account as writable only when
its `contacts.xml` declares an `EditSchema`
(`ExternalAccountType.areContactsWritable()` returns `mHasEditSchema`),
and leaves read-only accounts out of the "Save contact to" list. Adding
one made the account appear in Samsung's list too.

**Fix (cross-device, unreleased):** `contacts.xml` declares an
`EditSchema` limited to the fields pcontacts carries both ways. Tested on
the A40 on 2026-10-06: "PContacts" appeared in the list, a contact saved
there was created on Proton at the next sync, with its name and number.

Fossify Contacts offered the Proton account already before the fix
(under "Source" at the bottom of its editor); a contact created there was
created on Proton too, and the next new contact started with the Proton
account preselected.

## Settings shortcuts

Checked on 2026-10-06 from the app's buttons:

- **Default account for new contacts.** Android 11 has no system
  "Contacts storage" page (`android.provider.action.SET_DEFAULT_ACCOUNT`
  resolves to nothing; it arrived in Android 15), so the button is
  disabled with a hint. The choice is made in "Save contact to" when
  creating a contact (Issue 3).
- **Manage Contacts permission.** The per-permission app list
  (`android.intent.action.MANAGE_PERMISSION_APPS`) exists but is guarded
  by `GRANT_RUNTIME_PERMISSIONS`, which only system apps hold, and
  "Privacy controls" does not exist on Android 11. The button therefore
  opens Settings → Privacy, and its hint ("Then find Permission manager
  and open Contacts") matches the screen: Permission manager is the first
  entry, Contacts is inside it.
- **App counts differ, by design.** Permission manager showed "10 of 24
  apps allowed" while pcontacts listed 1 user-installed and 45
  OS-installed apps: Permission manager hides system apps until ⋮ →
  "Show system" is chosen.

## Earlier validation on this phone

On 2026-08-05 the same A40 confirmed that contacts in no group were
hidden until pcontacts wrote the account's `ContactsContract.Settings`
row, and that repeating the write is harmless. See
[mudita.md — Hardware validation performed](mudita.md#hardware-validation-performed-non-kompakt).
