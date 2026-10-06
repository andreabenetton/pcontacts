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

## Testing on this device

The instrumented UI tests do not run on this Android 17 base: the Espresso version
in use calls `InputManager.getInstance`, which Android 17 no longer has.
Run them on an older Android until Espresso is updated. Unit tests and
manual checks are unaffected.
