<!--
  SPDX-License-Identifier: GPL-3.0-only
  SPDX-FileCopyrightText: 2026 pcontacts contributors
-->

# GrapheneOS (Pixel)

Tested on a Pixel 9a running GrapheneOS (Android 17 base). GrapheneOS
keeps AOSP's contacts provider: no recycle bin, and the device's local
account is the one with no name and no type.

## Issue 1 — contacts saved by WhatsApp disappeared

WhatsApp's "save contact" screen offers a storage called "Phone". On this
phone it wrote the contact under the account `PHONE`/`PHONE`, which no
app registers and which is not the local account (null/null here).
Android's contacts provider hard-deletes the rows of such an account
every time the list of accounts changes, so the contact vanished at a
pcontacts sign-out, twice, with no tombstone (2026-09-22). pcontacts only
deletes rows of its own account; its sign-out was the trigger, as adding
or removing any other account would have been.

**Fix (cross-device, shipped in 2.1.0; ADR-0026):** **Move to Proton**
moves a `PHONE`/`PHONE` contact into the pcontacts account on the user's
confirmation, and the next sync creates it on Proton. Validated on this
phone on 2026-10-01 (ADR-0026 amendment): the moved contact kept `_ID`,
`contact_id` and its star, was created on Proton, and its birthday,
nickname and website survived the pull that rewrote it from Proton.

The user-facing explanation is in the FAQ:
[A contact I saved from WhatsApp disappeared after signing out, or after the 2.0 update](../FAQ.md#a-contact-i-saved-from-whatsapp-disappeared-after-signing-out-or-after-the-20-update).

## Issue 2 — the Contacts app treated the Proton account as read-only

GrapheneOS ships AOSP's Contacts app, which counts a third-party account
as writable only when its `contacts.xml` declares an `EditSchema`
(`ExternalAccountType.areContactsWritable()` returns `mHasEditSchema`).
Up to 2.2.1 pcontacts declared none. Observed with 2.2.0 on 2026-10-07:
"Create new contact" offered only the Google account (the "Saving to"
row opened no chooser), although the phone held 914 Proton contacts.

**Fix (cross-device, unreleased):** the `EditSchema` described in
[samsung.md — Issue 3](samsung.md#issue-3--proton-missing-from-save-contact-to).
Checked on this phone the same day with the fixed build, without saving
anything: "Create new contact" preselected the Proton account, and
editing an existing Proton contact opened "Edit contact — Saving to" the
Proton account with its own fields, so an edit goes to the pcontacts row.

The "Send via Proton Mail" rows (ADR-0021) still show. With a document
reader installed that declares a catch-all `VIEW` filter, the Contacts
app shows that reader's icon on those rows instead of the pcontacts
envelope, because both apps resolve the row's intent. Not caused by the
fix; not tapped during the check.

## Testing on this device

The instrumented UI tests do not run on this Android 17 base: the Espresso version
in use calls `InputManager.getInstance`, which Android 17 no longer has.
Run them on an older Android until Espresso is updated. Unit tests and
manual checks are unaffected.
