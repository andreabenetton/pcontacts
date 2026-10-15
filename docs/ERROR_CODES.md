<!--
  SPDX-License-Identifier: GPL-3.0-only
  SPDX-FileCopyrightText: 2026 pcontacts contributors
-->

# Error codes

When Proton refuses a change made on the phone, pcontacts stops sending it
and lists it as failed. In the app: **Sync** card → **N changes failed
permanently** → **Tap to review**. Each failed change shows two lines:

- a sentence in your language saying what Proton refused and what to do;
- below it, the code as Proton sent it, for example
  `HTTP 422, Proton code 2050`. Quote this line when you report a problem.

**Retry** sends the contact as it is on the phone now, so correct it first
when the sentence asks you to. **Discard** drops the change; the next sync
puts Proton's version back on the phone.

Proton does not document its codes for third-party apps. The meanings below
come from the source of Proton's web client (`[V]`) or from what Proton
answered a test account (`[A]`, with the date).

## Codes on a failed change

| Proton code | What the app says | What to do |
|---|---|---|
| 2050 `[V]` | Proton found an email address in this contact invalid. Seen live 2026-10-15 for `not-an-email`. | Correct the address on the phone, then tap Retry. |
| 2059 `[V]` | Proton did not accept the domain of an email address (the part after @). | Correct the domain on the phone, then tap Retry. |
| 2052 `[V]` | Proton found a website address in this contact invalid. | Correct the website on the phone, then tap Retry. |
| 2001 `[V]` | Proton found a detail of this contact invalid. Proton does not say which. | Check the contact's details on the phone, then tap Retry. |
| 2061 `[V]`, 2501 `[A]` 2026-10-01 | Proton no longer has this contact. It was deleted on Proton, for example on the web. | Tap Discard. |
| 2011 `[V]`, 2026 `[V]` | Proton does not allow this change on your account. | Check the account's plan and settings on Proton's web app; otherwise Discard. |
| 2060 `[U]` | Proton refused this change without saying why. Undocumented, and Proton's own apps do not handle it; under investigation in [#71](https://github.com/andreabenetton/pcontacts/issues/71). | Tap Retry. If it fails again, report it (below). |
| any other code | Proton refused this change without saying why. | Tap Retry. If it fails again, report it (below). |

The `[V]` meanings are the names of these codes in Proton's web client,
`packages/shared/lib/errors.ts` in
[ProtonMail/WebClients](https://github.com/ProtonMail/WebClients).

## Failed changes without a Proton code

| Shown below the sentence | What the app says | What to do |
|---|---|---|
| `HTTP 400` (or another status) with no code | Proton refused this change without saying why. | Tap Retry. If it fails again, report it. |
| `HttpException: …` | The same. A change that failed before version 2.0 kept this form. | Tap Retry, or Discard. |
| `contact not found locally` | This contact is no longer on this phone. | Tap Discard. |
| `network error` | Proton could not be reached. | Tap Retry once the phone is online. |
| `group needs an email` | Proton groups need an email address, and this contact has none. | Tap Discard; see [contact groups](FAQ.md#how-do-contact-groups-sync-and-what-are-the-limits). |
| anything else | Something went wrong on this phone. | Tap Retry. If it fails again, report it. |

## Codes the app handles on its own

These never appear as a failed change:

- **9001** `[V]`: Proton asks for a captcha. A notification, **Verification
  required**, opens it.
- **12087** `[V]`: a captcha answer was too old. The app drops it and asks
  again when Proton does.
- **5003, 5004** `[V]`: Proton no longer accepts this version of the app.
  The notification **pcontacts needs an update** appears; only an update
  helps.
- **2500** `[A]` 2026-10-14: a contact with the same identifier is already
  on Proton. The app links the phone contact to the one on Proton instead of
  creating a second copy.
- **HTTP 429 and 5xx**, and network errors: Proton is busy or unreachable.
  The change stays queued and is sent again later, waiting longer each time
  (at most an hour).

## Reporting a code

Open an issue at
[github.com/andreabenetton/pcontacts/issues](https://github.com/andreabenetton/pcontacts/issues)
with:

- the line below the sentence, for example `HTTP 400, Proton code 2060`;
- the app version (under the app name in pcontacts);
- what kind of change it was: a new contact, an edit, a deletion, and which
  kind of detail changed (a phone number, a photo, a birthday).

Do not paste the contact's name, numbers or addresses: the code and the
kind of change are enough.
