# ADR-0016: No AccountManager token retrieval — getAuthToken returns re-auth intent

- **Status:** Accepted
- **Date:** 2026-05-24
- **Deciders:** project owner
- **Related:** ADR-0004, ADR-0009

## Context

`ProtonAccountAuthenticator.getAuthToken()` is called by the Android
framework (or by third-party apps via `AccountManager.getAuthToken()`)
to retrieve a bearer token for the registered account type.

The standard pattern is for this method to return the cached
`AccessToken` in a Bundle keyed by `AccountManager.KEY_AUTHTOKEN`.
However, Android's `AccountManagerService` then **caches this token
in the system accounts database** (`/data/system/users/0/accounts_ce.db`),
which is plaintext-on-disk and accessible to any process running as
the same UID or with root privileges.

ADR-0009 established that all secrets live exclusively in
`EncryptedSharedPreferences` backed by a Keystore AEAD key. Returning
the token via `getAuthToken()` would create a second, weaker copy of
the AccessToken outside the app's encrypted storage, contradicting
the security model.

Our only internal consumer of the Proton AccessToken is the
SyncAdapter, which reads directly from `SecretStore` via
`SyncBootstrap` — it never calls `AccountManager.getAuthToken()`.
No external app has a legitimate reason to request a Proton API
access token from this account type.

## Decision

`getAuthToken()` does not return the Proton AccessToken. Instead, it
always returns a `KEY_INTENT` Bundle pointing to `LoginActivity`,
signalling to the caller that interactive re-authentication is
required. This is a valid response per the `AbstractAccountAuthenticator`
contract and causes `AccountManager.getAuthToken()` callers to receive
an auth-required callback rather than a stale or leaked token.

The app's own sync path (`ProtonSyncAdapter` → `SyncBootstrap` →
`SecretStore`) bypasses `AccountManager.getAuthToken()` entirely and
reads the current AccessToken from `EncryptedSecretStore`.

## Alternatives considered

- **Return the real AccessToken via KEY_AUTHTOKEN.** Rejected — the
  system caches it plaintext-on-disk, violating ADR-0009. The token
  also has a short lifetime and is refreshed by the OkHttp interceptor;
  the AccountManager-cached copy would go stale immediately.
- **Use AccountManager.setAuthToken() after each refresh to keep
  the cached copy current.** Rejected — widens the secret surface
  for no internal benefit. The only consumer (SyncAdapter) already
  reads from SecretStore.
- **Return KEY_ERROR_MESSAGE with a generic string.** The previous
  stub did this, but some AccountManager callers treat error-message
  responses as transient failures and retry. Returning KEY_INTENT is
  the proper "user action required" signal.

## Consequences

- Third-party apps that call `AccountManager.getAuthToken()` for our
  account type will receive a re-authentication intent, not a token.
  No known third-party app needs this capability.
- The SyncAdapter continues to work because it never goes through
  `getAuthToken()`.
- The AccessToken exists in exactly one location: `EncryptedSharedPreferences`
  via `SecretStore`. No plaintext copy in the system accounts database.
- If a future feature requires AccountManager token retrieval (e.g.,
  inter-app contact sharing), this ADR must be superseded with a
  risk-accepted justification.

## Validation

- `AccountManager.getAuthToken()` for our account type returns null
  and triggers the re-auth intent (manual test against a signed-in
  account).
- Grep: no call to `AccountManager.setAuthToken()` exists in the
  codebase.
- The system accounts database (`accounts_ce.db`) contains no
  `authtoken` row for our account type after a full sync cycle.
