<!--
  SPDX-License-Identifier: GPL-3.0-only
  SPDX-FileCopyrightText: 2026 pcontacts contributors
-->

# Roadmap

> This is the public-facing subset of the internal plan. Open an issue before starting work on any unchecked item.

What has shipped is recorded in the [CHANGELOG](../CHANGELOG.md).

## Next

- [ ] Multi-account support
- [ ] FIDO2/WebAuthn 2FA

## To investigate

Risks the reviews of 2026-11-17 and 2026-11-18 found in the code; whether Proton's side can
produce them is not known.

- [ ] Two versions of a contact with the same `ModifyTime`: the pull skips on an equal time. Needs a live test or a guarantee from Proton
- [ ] A card type this version does not know is dropped when its contact is written back. None is in use today; a write should be refused while such a card is present

## To validate

Built and tested here, not yet against the real thing.

- [ ] Accounts whose keys an organization manages: try a real organization account
- [ ] AEAD-encrypted cards: the integrity vectors are made by BouncyCastle; add some from OpenPGP.js or a live account
- [ ] A contact too large for the phone's contacts is skipped and counted as failed: find what size Proton accepts, and name the contact to the user

## Possible improvements

- [ ] Send unchanged signed cards back byte for byte instead of signing them again (Proton's web app signs again too)
