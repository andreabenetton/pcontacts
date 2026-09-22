<!--
  SPDX-License-Identifier: GPL-3.0-only
  SPDX-FileCopyrightText: 2026 pcontacts contributors
-->

# FAQ

Practical questions from real devices. Each answer says what happens, why,
and what to do. Where a maintainer can verify the claim on a phone, the
check is given at the end of the answer.

## A contact I saved from WhatsApp disappeared after signing out, or after the 2.0 update

The contact was never in the Proton account. WhatsApp's own "save contact"
screen offers a storage called "Phone" ("Telefono" on an Italian phone).
That is not Android's device storage and not any account your phone knows:
WhatsApp writes the row under an account of type `PHONE` that no app
registers with Android. Android's contacts provider treats rows of an
unregistered account as leftovers and deletes them, without a trace, the
next time the list of accounts on the phone changes. Signing out of
pcontacts removes the Proton account and is such a change; adding or
removing a Google account or any other account would do the same. The
automatic sign-out of the 2.0 update triggered it too.

So this is WhatsApp's bug, twice over: its save screen ignores Android's
default account for new contacts, and the storage it preselects is an
account Android does not know, which makes the row disposable. pcontacts
only ever deletes rows of its own account. It cannot protect a row it does
not own from the provider's cleanup; its sign-out is merely the trigger,
as any account change would be.

What to do:

- Move the contact into the Proton account before it is lost: open it in
  the Contacts app (Google Contacts, Fossify Contacts, ...) and use "Move to
  another account", choosing the Proton account. pcontacts pushes it at the
  next sync and confirms the create.
- For new contacts, set the Proton account as the default for new contacts
  (see below) and save from the Contacts app. WhatsApp's own picker ignores
  that default; if it lists the Proton account, choose it there.

Maintainer check: `adb shell content query --uri content://com.android.contacts/raw_contacts --projection _id:account_type:account_name:sourceid:dirty --where "display_name LIKE '%Name%'"`.
A row with `account_type=PHONE` and no `sourceid` is the doomed one. A
probe row with that account type on a test phone vanishes the moment an
account is removed.

## WhatsApp shows a phone number instead of the name

WhatsApp shows a name only when the number matches a contact in the phone's
address book. When the contact that held that number is gone (see the
previous question), or the Proton contact holds a different number than the
one WhatsApp knows, WhatsApp falls back to the number. Check which numbers
the Proton contact carries; two people with the same surname are easy to
mix up.

## The Contacts app shows two or three entries for one person

Every messenger keeps its own read-only mirror row per matched contact:
"WhatsApp", "Telegram", "Signal". Apps like Fossify Contacts list those
sources separately; Google Contacts folds them under one name. The mirrors
follow the real contact and disappear with it. The real contact is the row
under the Proton account, or under "Telefono"/"Phone" if it was saved the
way described above.

## How do I make new contacts go to Proton?

The main screen has "Default account for new contacts". On Android 15 and
later it opens the system page where the Proton account can be chosen; on
older Android the button says so and the choice is made in the Contacts
app's settings instead. After that, a contact saved from the Contacts app,
or handed to it by another app, lands in the Proton account and is pushed
at the next sync. Apps that write contacts themselves, WhatsApp included,
may still pick their own storage.

## What does sign-out delete, and what does it keep?

Sign-out revokes the session on Proton, deletes every contact row of the
Proton account from the phone, wipes the app's bookkeeping and secrets, and
removes the Android account. It leaves the rows of every other account
alone, apart from the provider cleanup described in the first question.

Local edits in the Proton account that were not sent yet are lost with the
rows. The sync card shows pending and failed changes before you sign out;
run "Sync now" and let the card reach "Up to date" first.

## Why did 2.0 ask me to sign in again and download everything?

The secret store changed format. The app signs the old account out on
its first start (or at the first background sync, with a notification),
and a fresh sign-in downloads all contacts again. Contacts already on
Proton come back unchanged. Local edits that had not been sent are lost,
as with any sign-out.

## When does pcontacts show a notification?

Only from background work, one trigger each:

- **Sign in required**: a sync finds the session unusable, or the 2.0
  update needs the one-time sign-in.
- **Verification required**: Proton demands a captcha (code 9001), or
  rejects the app version.
- **Sync keeps failing**: a network or internal error persisted through
  Android's own retries. A single failed run never notifies.
- **Contacts with problems**: a background sync left failed, quarantined
  or conflicted contacts; posted once per change of the count, removed by a
  clean run. A sync started from the app stays silent because the card is
  on screen.
- **Dependency vulnerability**: once per app version, when the shipped
  audit snapshot has an open CVE.
- **New advisories**: only with the opt-in osv.dev check on, once per new
  advisory.

## The sync interval slider has an "Off" position. What does it do?

"Off" turns Android's Contacts sync off for the Proton account, exactly as
the switch under Settings, Accounts. Any interval turns it back on. A
switch flipped in Android Settings shows as "Off" in the app. When the
phone-wide "Auto-sync data" switch is off, the sync card says so and links
to the system page: nothing syncs on its own then, only "Sync now" works.

## How can I see where a contact is stored?

Fossify Contacts shows the source under each entry. Google Contacts shows
the account below the name when a contact is opened. The Proton account
appears under the e-mail address you signed in with.
