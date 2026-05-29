# ADR-0020: Decrypt path tries all unlocked user + address keys

- **Status:** Accepted
- **Date:** 2026-05-29
- **Deciders:** pcontacts maintainers
- **Related:** ADR-0002 (native Kotlin crypto), ADR-0007 (client-side
  decryption only), ADR-0009 (secrets storage / heap discipline),
  Plan §10 §2.5 (multi-key fallback)

## Context

Plan §5 / §17 task 17 shipped the contact decrypt path with a single
unlocked key: `ContactDecryptBootstrap` fetched the user's primary
key from `/core/v4/users`, unlocked it with the persisted
`keyPassword`, and passed `unlocked.allPrivateKeys` (primary +
encryption subkeys of that one ring) to `OpenPgpCardCryptoOp`. On a
real Proton mailbox this aborted the first sync with:

```
BouncyCastleOpenPgpService#decryptToBytes:153
  IllegalStateException: no encrypted data block for any of our N key(s)
```

Root cause: Proton encrypts contacts to **address keys** (one per
email address the user owns) at least as often as to user keys.
WebClients `packages/shared/lib/contacts/decrypt.ts` resolves
decryption by feeding `getAllDecryptedAddressKeys + userKeys` into
the OpenPGP message-decrypt call; the keyID-to-recipient match
succeeds against whichever key the server happened to encrypt to.
Our single-user-key path could not see that match, so any
address-key-encrypted contact stalled the sync run.

Address-key passphrases use a two-level mechanism (`[V]`
WebClients `packages/shared/lib/keys/keys.ts:decryptAddressKeyToken`):

1. Each `AddressKey.Token` is a PGP message encrypted to the user's
   primary public, signed by the user's primary.
2. Decrypting the Token under the unlocked user primary yields a
   passphrase string (Proton ships hex-encoded random bytes
   per `[U]` — exact charset not document-locked, but every
   observed sample is US-ASCII).
3. That passphrase unlocks the address key the way `keyPassword`
   unlocks user keys.

The decrypt primitives in `:core:crypto`
(`BouncyCastleOpenPgpService.decryptAndVerify`,
`BouncyCastleKeyUnlock.unlock`) were already sufficient — the
missing piece was the orchestration layer plus the
`GET /core/v4/addresses` surface.

## Decision

`ContactDecryptBootstrap` fans out the per-sync-run key unlock to:

- every active user key on the account (not just the primary),
- and every active address key whose `Token` decrypts successfully
  under the unlocked user primary (or, for legacy v1 keys without
  a `Token`, unlocks directly under the user `keyPassword`),

unions all resulting `allPrivateKeys` into one `decryptionKeys`
list and all resulting publics into one `verificationKeys` list,
and passes both to `OpenPgpCardCryptoOp` as before. The shared
`UnlockedKeySet` returned by the new `unlockAllKeys()` helper is
also consumed by `SyncBootstrap.createBidirectionalEngines`, so
the read and write paths share one fan-out per sync run.

Skip-and-continue policy: an address key that fails to unlock
(unreadable Token, missing user-key recipient, future key format)
is **skipped + logged** with a non-sensitive counter; sync
continues with the remaining keys. A user key that fails to
unlock remains fatal — it indicates a stale `keyPassword` and the
user must re-log-in (`KEY_UNLOCK_FAILED`).

Token signature verification is **deferred** (`[D]` for MVP). See
Consequences.

## Alternatives considered

- **Single primary user key only (status quo).** Rejected: this
  is the exact bug — real Proton mailboxes encrypt contacts to
  address keys, so the existing path aborts sync on the first
  such contact.
- **Unlock all user keys, ignore address keys.** Rejected: would
  still fail on every address-key-encrypted contact, which is the
  common case on production mailboxes.
- **Server-side decrypt via `GET contacts/v4/contacts/export`.**
  Already rejected by ADR-0007 (client-side decryption only). Not
  reopened.
- **Lazy per-card unlock attempt.** Decrypt each card; on
  "no encrypted data block" exception, try to unlock more keys.
  Rejected: doubles the number of exceptions in the hot path,
  conflates "permission to decrypt" with "this specific recipient
  matched", and complicates the no-sensitive-log invariant.
- **Verify the Token signature inline.** Rejected for MVP: would
  add a second crypto pass per address key, and the threat surface
  for a Token-substitution attack is identical to the
  contact-ciphertext-substitution attack the server can already
  mount. Recorded as a follow-up (Consequences).

## Consequences

Easier:
- Contacts encrypted to address keys decrypt and reach the
  ContactsContract writer (the original Issue 1 displayName fix is
  finally observable on-device).
- Key-rotation accounts (multiple active user keys) work without
  any further change — non-primary user keys also unlock and join
  the union.

Harder / new obligations:
- Additional sync-run cost: ~50 ms per address key for
  BouncyCastle's RSA-2048 unlock, one-shot per sync run.
  Acceptable for read-only sync; if write-path latency becomes a
  problem, cache the unlocked handles in `SyncBootstrap` for the
  bidirectional engine's lifetime.
- The signing-key + Token decryption keys are held in memory for
  the duration of the sync run (no change in lifetime — same as
  before, the primary was already retained — but a wider key set
  is now resident). Heap-only, zeroized passphrases preserved by
  the new `unlockModern` / `unlockLegacyV1` helpers.
- Token charset assumption: `[U]` Tokens are assumed US-ASCII per
  WebClients's `String(token)` conversion. If a future Proton
  release ships binary Tokens, the affected address keys silently
  fall into the skip-and-continue bucket; sync still completes
  on whatever keys did unlock.
- Token signature verification is **deferred** (`[D]`). A malicious
  Proton server could substitute a Token with one that unlocks a
  key under the server's control; the server already controls the
  ciphertext, so the threat is no greater than the server simply
  returning attacker-chosen ciphertext. Follow-up: verify
  `AddressKey.Signature` under the user primary public before
  treating the unlocked address key as trusted, and gate the
  decrypted card's `isVerified` flag on that result.

## Validation

- Unit: `ContactDecryptBootstrapTest` includes a regression case
  encrypting the contact card **only** to a freshly-generated
  address public; the bootstrap must produce a `DecryptedContact`
  that round-trips through merge and projection.
- Unit: `address_key_with_undecryptable_token_is_skipped_and_user_key_decrypt_still_works`
  guards the skip-and-continue invariant.
- Unit: `legacy_v1_address_key_with_null_token_unlocks_with_user_keyPassword`
  covers the WebClients `hasMigratedKeys=false` branch.
- Manual: trigger a sync against a real account (`pc0ntact@proton.me`).
  Logcat should report `"unlocked U=1 user keys + A=N address keys
  (skipped=0)"` and contacts decrypted to address keys should
  reach the system Contacts app.
- A future client-side reproduction of the
  "no encrypted data block" error on a contact decrypted via the
  new path means this ADR needs a successor — either Token
  format drift, a third key class, or a key-transparency surface
  we haven't modeled.
