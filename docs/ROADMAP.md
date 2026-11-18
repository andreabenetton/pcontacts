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

From the reviews of 2026-11-17 and 2026-11-18; not confirmed as failures yet.

- [ ] Contacts whose `ModifyTime` does not change between two versions: the pull skips on an equal time
- [ ] Card types this version does not know are dropped when a contact is written back
- [ ] Removing a property removes every property of its vCard group, across cards
- [ ] Unchanged signed cards could be sent back byte for byte instead of signed again
- [ ] A single contact larger than the batch budget travels alone; one larger than Binder's buffer still fails, and stops the batches after it
- [ ] Accounts whose keys an organization manages: validate against a real account
- [ ] AEAD-encrypted cards: the integrity vectors are made by BouncyCastle; none from OpenPGP.js or a live account
- [ ] Merge bases saved by 2.6.0 for rows edited during a pull record "no photo" where the photo was not known
