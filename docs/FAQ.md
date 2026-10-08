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
registers with Android. Android's real device storage is the account with
no name and no type on AOSP and GrapheneOS, or the one the maker configures
(`vnd.sec.contact.phone` on Samsung); a bare `PHONE` is neither. Android's
contacts provider treats rows of an unregistered account as leftovers and
deletes them, without a trace, the next time the list of accounts on the
phone changes. Signing out of
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

- Move it with pcontacts: in *Import details from linked contacts*, open the
  contact and tap **Move to Proton**. The contact itself moves into the
  Proton account — star, ringtone and links included — and the next sync
  creates it on Proton. The dialog names anything Proton would not keep
  (other dates, relations, SIP addresses); **Create** copies the details
  instead and leaves the original alone (ADR-0026).
- Or move the contact yourself before it is lost: open it in
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
"WhatsApp", "Telegram", "Signal". Apps like Fossify Contacts permit to list
those sources separately; Google Contacts folds them under one name. The mirrors
follow the real contact and disappear with it. The real contact is the row
under the Proton account, or under "Telefono"/"Phone" if it was saved the
way described above.

## How do I make new contacts go to Proton?

The main screen has "Default account for new contacts". On Android 15 and
later it opens the system page where the Proton account can be chosen; on
older Android the button says so, and the choice is made in the Contacts
app when you create a contact. Samsung Contacts has a "Save contact to"
list at the top of the new-contact screen, where the account appears as
"PContacts" (pcontacts 2.2.1 and earlier were missing from that list).
Fossify Contacts has it under "Source", at the bottom of its editor, and
preselects the account chosen last time.

A contact saved in the Proton account is pushed at the next sync. Apps
that write contacts themselves, WhatsApp included, may still pick their
own storage.

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

## I turned on two-password mode (or changed my password) and pcontacts asks me to sign in again

Turning Proton's **two-password mode** on or off, changing your password,
or signing out of all devices on proton.me ends every session, the one
pcontacts uses included. The next sync stops and pcontacts shows **Sign
in required**: sign out in the app and sign in again. In two-password
mode, sign-in asks for your second password after the login password (and
after the 2FA code), as Proton's own apps do.

Expect a full resync: signing out removes the Proton contacts from the
phone and the new sign-in downloads all of them again. Contacts already
on Proton come back unchanged; local edits that had not been sent yet are
lost, as with any sign-out — let a sync finish before changing these
settings.

## When does pcontacts show a notification?

Only from background work, one trigger each:

- **Sign in required**: a sync finds the session unusable, or the 2.0
  update needs the one-time sign-in.
- **Verification required**: Proton demands a captcha (code 9001).
- **pcontacts needs an update**: Proton no longer accepts this version of
  the app; only an update helps.
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

## I deleted a contact on a Samsung and pcontacts asks what to do

Samsung Contacts moves a deleted contact into its Recycle bin, where no
sync app can see it. pcontacts cannot tell that apart from a contact
another app removed, so it asks instead of guessing: "Delete from
Proton" removes it on Proton too (after the usual one-hour grace, which
you can cancel), "Put it back" restores it on the phone. Restoring it
from the Recycle bin yourself also settles the question. With the
Recycle bin switched off in Samsung Contacts settings (called "Trash" on
some versions), nothing is asked: a deletion reaches Proton as on any other
phone, after the usual grace hour. Switching it off permanently deletes
whatever the bin holds; Samsung asks first.

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

## "Send via Proton Mail" shows another app's icon, or asks which app to open

Another installed app claims to open every file and link, so Android
offers both it and pcontacts for that row. OpenDocument Reader does this
when its "Offer to open any file" setting is on. Android's own Contacts
app then shows the icon of whichever app it lists first, and a tap asks
"Open with". The other app cannot read the contact: the Contacts app does
not give it access.

Tap the row once, choose pcontacts and then **Always**. From then on the
row goes straight to Proton Mail, and the Contacts app shows pcontacts'
icon. Turning the other app's catch-all setting off works too.

## How do contact groups sync, and what are the limits?

Proton and Android keep groups in different places, and pcontacts translates
between the two:

- **Proton puts email addresses in a group, Android puts whole contacts.** On
  Proton, each address of a contact can be in a group or not (the web app asks
  which addresses to add when a contact has several). On the phone, a contact
  is either in a group or not.
- **Adding a contact to a group on the phone adds all of its email
  addresses** to the Proton group. **Removing it removes all of them**, also
  any that were added on Proton one by one.
- **An address added to a contact later is not added to its groups.** Add it
  to the group on Proton's web app, or remove the contact from the group on
  the phone, sync, and add it again.
- **A contact without an email address cannot be in a Proton group.** If you
  put one in a group on the phone, the sync card lists the change as failed:
  "Proton groups need an email address". Tap **Discard**, and the next sync
  puts Proton's groups back on the phone. Any other change made in the same
  edit (a new phone number, a removed address) still reaches Proton.
- **Groups themselves are managed on Proton's web app.** A group created on
  the phone ("Create new…" under Label in Android's Contacts app, or a new
  group in Samsung Contacts) stays on the phone: it is not sent to Proton,
  and its members are dropped the next time the contact is updated from
  Proton. Renaming or deleting a Proton group on the phone is not sent to
  Proton either.
- **Contact groups need a paid Proton plan.** On a free plan Proton has no
  contact groups, and there is nothing to sync.

Changes go to Proton at the next sync, and changes made on Proton come back
the same way. A contact changed on both sides keeps both: pcontacts sends
only what you changed on the phone, so a group added on the web meanwhile
stays. In Android's own Contacts app, groups are called **labels** and appear
under **More fields** when editing a contact.

