# ADR-0007: Decrypt client-side only — never use the server-side export endpoint

- **Status:** Accepted
- **Date:** 2026-05-22
- **Deciders:** project owner
- **Related:** ADR-0002, ADR-0009

## Context

Proton exposes `GET contacts/v4/contacts/export` which `[A]` returns already-decrypted vCards in some form (we have not validated the exact behavior; the parameter and path are present in `packages/shared/lib/api/contacts.ts`). The fact that the endpoint exists raises a design question: should the app use it (cheap, no on-device crypto needed) or always pull encrypted Cards and decrypt locally?

Threat-model considerations:

- Proton's value proposition is end-to-end encryption. If the export endpoint truly returns server-decrypted vCards, the server has access to plaintext contact content. We do not want to participate in any flow that transmits decrypted plaintext over the wire — even though TLS protects it in transit, the server endpoint sees plaintext at termination.
- The web client itself does **not** use the export endpoint for normal display flow — it pulls `Cards[]` and decrypts in-browser. The export endpoint is for explicit user-initiated exports, where the user is consciously choosing to receive plaintext.
- Even if Proton's export endpoint requires a server-held key wrap (we can't tell from the JS alone), routing every sync through it would shift trust from the on-device key to the server's authentication of the request — a different threat model.

Performance considerations: encrypted Cards + on-device decrypt is `O(contacts)` per sync. The export endpoint would be `O(1)` per page. For a typical Proton account (≤ a few thousand contacts), the on-device crypto cost is negligible (BouncyCastle decrypts thousands of small PGP messages per second on modern Android).

## Decision

The app **always** fetches encrypted `Cards[]` via `GET contacts/v4/contacts/{id}` (and the metadata list via `GET contacts/v4/contacts/emails`) and **always** decrypts locally with the user's keys.

The app **never** calls `GET contacts/v4/contacts/export`. The Retrofit interface for `ProtonContactsClient` does not declare this method.

The decrypted vCard byte stream:

- Lives only in heap memory during a sync run.
- Is not logged (ADR-0015 Lint rule enforces this).
- Is not written to disk in MVP (ADR-0006).
- Is zeroized after use (best-effort — JVM has no guaranteed zeroize; we still null-out references and wipe `ByteArray` contents).

## Alternatives considered

- **Use `/export` as the primary path for speed.** Rejected on threat-model grounds.
- **Use `/export` as a fallback if `/contacts/{id}` fails.** Rejected — same threat model, no real benefit (failures usually mean network/auth issues, not "this one contact won't decrypt").
- **Offer `/export` as an opt-in setting for users who explicitly accept the trade-off.** Considered. Rejected for MVP to keep the code path single. Re-evaluate if a real user need emerges.

## Consequences

- Every sync run does `O(N)` PGP decrypt operations. On a 2GB Android device with 1000 contacts, this is ~1 second of CPU. Acceptable.
- The decrypted bytes path is auditable end-to-end. There is exactly one code path through which decrypted data flows: `ProtonCryptoService.decryptCard` → in-memory `VCard` → `AndroidContactsWriter` → `ContentResolver.applyBatch`.
- We do not need any sort of "server trust" UI explaining why the export endpoint is or isn't used — it simply doesn't exist in our codebase.
- If Proton's `/export` behavior changes (e.g., the server starts returning encrypted exports), we're unaffected because we don't use it.

## Validation

- Static check: a CI job greps the codebase for the string `"contacts/v4/contacts/export"` and fails the build if found in any file outside this ADR.
- Code review: any PR that adds an `/export` reference is automatically blocked by the static check above.
