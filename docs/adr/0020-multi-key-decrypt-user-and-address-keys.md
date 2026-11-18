# ADR-0020: Decrypt path tries all unlocked user + address keys

- **Status:** Accepted (amended 2026-09-22 — Token signature verification is mandatory; amended 2026-11-17 — the encryption key is chosen from signatures that verify, an encrypted message must prove its integrity, and an organization member's user keys open with their member tokens)
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

Token signature verification was **deferred** (`[D]` for MVP) until
the amendment of 2026-09-22 below made it mandatory.

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

## Amendment (2026-09-22): Token signature verification is mandatory

The deferral above understated the consequence: an unlocked address
key was added to the set of **verification** keys, so a substituted
Token would not only decrypt attacker-chosen ciphertext but also make
cards signed by an attacker-controlled key read as `isVerified`.
The `[D]` is closed. Rules:

- For a modern address key (`Token != null`) the decrypted Token is
  accepted only when `AddressKey.Signature` is present and verifies
  as `SIGNED_AND_VALID` under the user's unlocked keys. `[V]`
  WebClients `packages/shared/lib/keys/addressKeys.ts`
  (`decryptAddressKeyToken` requires `SIGNED_AND_VALID`).
- A key whose Token has no signature, an invalid one, or one made by
  a key we do not hold is **skipped**: it enters neither the
  decryption-key set nor the verification-key set, and the skip is
  logged with the key id only. Sync continues on the remaining keys.
- Legacy v1 keys (`Token == null`) are unchanged: they unlock with
  the user's own `keyPassword`, which is the trust anchor itself.
- `[U]` the signature is a text-mode signature over the Token
  string; a verifier mismatch fails closed (skip), never open.

Validation: `ContactDecryptBootstrapTest` covers a valid signature
(key used), an invalid signature on a decryptable Token (key skipped,
its cards not verified) and a missing signature (key skipped).


## Amendment (2026-10-06): extra user keys that do not open are skipped

`[V]` WebClients `getDecryptedUserKeys.ts`: the primary user key must
open with `keyPassword` — if it does not, nothing can be decrypted and
the run stops with `KEY_UNLOCK_FAILED` (re-authentication). Any other
active user key that does not open (e.g. one from before a password
reset) is now **skipped** and logged with its id only, as address keys
already were; before, one such key stopped every sync (issue #65).
Validation: `ContactDecryptBootstrapTest.an_extra_user_key_that_does_not_open_is_skipped_not_fatal`.

## Amendment (2026-11-17): the encryption key is chosen from signatures that verify

A card is encrypted to one key of the user's primary ring, picked as
OpenPGP.js `Key.getEncryptionKey` picks it `[V]` (openpgpjs
`src/key/key.js`, `src/key/helper.js`): the newest usable subkey whose
binding signature flags it for encryption, else the primary if its own
self-signature does (issue #71, 2.5.2).

Every signature that decides this must now verify against the primary
key; matching the issuer key ID is no longer enough. The ring arrives
from the server, so a subkey whose binding only names the primary as
issuer, or a revocation the primary did not make, is ignored:

- the primary needs a self-certification (user ID or direct-key) that
  verifies, no revocation of its own that verifies, and no expiry passed
  according to that verified signature; otherwise the ring has no
  encryption key at all;
- a subkey needs a binding signature that verifies, flags and key expiry
  are read from that signature, and a subkey revocation counts only if
  it verifies;
- a ring with no usable encryption key encrypts nothing: the write fails
  instead of falling back to the primary key.

Decryption is unchanged: every unlocked key of the ring, current or not,
still decrypts what was encrypted to it.

Validation: `BouncyCastleKeyUnlockTest` — a planted subkey with a forged
binding, a forged revocation, a revocation by the primary, an expired
binding, a forged self-certification (review of 2026-11-17, F1).

## Amendment (2026-11-17, second): an encrypted message must prove its integrity

Decryption returns plaintext only from an integrity-protected packet
whose check passed. Reading the literal data never checked the MDC
(modification detection code) of a SEIPD v1 packet, and a packet without
any protection (symmetrically encrypted data, tag 9) decrypted as if it
were sound. Now:

- a packet that is neither integrity-protected nor AEAD is refused, as
  OpenPGP.js refuses it by default `[V]`
  (`config.allowUnauthenticatedMessages = false`);
- after the literal data is read, the MDC is verified; on failure the
  plaintext is wiped and the decryption fails;
- an AEAD packet checks its tags while it is read.

A failure is handled like any other decryption failure: the pull skips
that contact, a push backs off, an address key whose token fails is
skipped. This applies to every caller — contact cards, address-key
tokens and, later, member tokens.

Validation: `BouncyCastleOpenPgpServiceTest` — a flipped MDC byte with
intact literal data and a valid signature, altered and truncated
ciphertext, a packet without integrity protection; the captured
OpenPGP.js vectors still decrypt (review of 2026-11-17, F2).

## Amendment (2026-11-17, third): an organization member's user keys open with their member tokens

Every user key was opened with the key password. On an account whose
keys an organization manages that cannot work: WebClients
`getDecryptedUserKeysHelper` `[V]` (`getDecryptedUserKeys.ts`,
`memberToken.ts`, `getDecryptedOrganizationKey.ts`, WebClients @ 444df12)
opens them another way, and pcontacts now does the same, at sign-in and
at every sync:

- `User.OrganizationPrivateKey`, when present, opens with the key
  password; if it does not, no user key opens (sign-in is refused, a
  sync stops with `KEY_UNLOCK_FAILED`), as in WebClients;
- a user key with a `Token` opens with that member token: the token is
  decrypted with the organization key and must carry a valid signature
  by it (`decryptMemberToken`), else the key does not open;
- a user key without a token, and every key of an ordinary account,
  opens with the key password as before.

Nothing new is stored: the key password is still the only secret kept,
and the organization key and tokens are opened in memory on each run and
wiped. The member token is a message signed inline, which
`OpenPgpService.decryptAndVerifyInline` reads, integrity-checked as every
decryption is.

`[A]` Not validated against a live organization account: the test
account and the owner's account are ordinary ones. Validation so far:
`OrganizationKeyUnlockTest` (generated organization and member keys: a
valid chain, a token signed by another key, an organization key that
does not open, an ordinary account) and the `member-token-inline-signed`
vector, made with OpenPGP.js as `encryptMemberToken` makes it and read by
BouncyCastle (review of 2026-11-17, F8).

## Amendment (2026-11-18): the whole envelope is read before plaintext is returned

The second amendment of 2026-11-17 said an AEAD packet checks its tags
while it is read. That holds only for the chunks actually read. The
content this app wants sits at the start of the message; chunks after it
and the last tag, which covers the whole message, were never read, so an
AEAD message altered or cut short past that content decrypted as sound.
Reproduced with BouncyCastle 1.87 before the fix: a message with its
last tag altered was accepted, in the RFC 9580 layout.

Decryption now reads the decrypted stream to its end before it returns
anything, for every packet type and on both the detached and the inline
path, as BouncyCastle's own `PGPEncryptedData.verify()` does before it
compares the MDC. Then the MDC is compared where there is one. On any
failure, an exception included, the plaintext read so far is wiped.

Validation: `AeadEnvelopeIntegrityTest` — AEAD messages in both layouts
BouncyCastle writes (RFC 9580 and LibrePGP), with a trailing packet over
several later chunks: intact ones decrypt; a last tag altered, a late
chunk altered and a message cut short are refused, plain, compressed and
signed inline (review of 2026-11-18, R4). `[A]` Proton's clients are not
known to send AEAD messages today; the vectors are made by BouncyCastle,
not by OpenPGP.js.
