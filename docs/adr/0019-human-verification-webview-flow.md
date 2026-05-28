# ADR-0019: Human-verification (9001) handled via in-app WebView

- **Status:** Accepted
- **Date:** 2026-05-28
- **Deciders:** repo maintainer
- **Related:** ADR-0007 (decrypt client-side only), ADR-0009 (secrets storage), ADR-0012 (HTTP stack)

## Context

Proton's API responds with `Code: 9001 "Human verification required"` when an
IP / session needs to solve a captcha (and, less commonly, an email / SMS
challenge). The trigger is opaque and per-account: fresh IPs, unusual
geo-velocity, and 2FA-enabled accounts can all see it. The official Proton
web client (`ProtonMail/WebClients`, `packages/shared/lib/api/helpers/withApiHandlers.ts`)
and the official Proton Android stack
(`ProtonMail/protoncore_android/human-verification/...`) both treat 9001
uniformly across every endpoint — login, refresh, contacts — and resolve
it transparently via a verification token attached to subsequent requests.

pcontacts originally:

1. Detected 9001 at the OkHttp interceptor layer (`HumanVerificationInterceptor`).
2. Demoted it at every higher caller (`fetchInfo → info_failed`,
   `submitAuthAndVerifyProof → auth_failed`, `submitTwoFactorCode →
   two_factor_failed`, `TokenRefresher → false`, `ContactDetailSyncEngine
   → fetchFailures++`, `ContactWriteEngine → quarantined`).
3. On the one path where the exception did reach the UI (sync adapter
   notification), the recovery flow opened the captcha in a **Chrome Custom Tab**.

The Custom Tab approach silently fails end-to-end: the captcha solution
lives in Chrome's cookie jar, which the app's `OkHttpClient` never shares.
The next request still triggered 9001; if the captcha was the one
expression of HV the account had, the user would loop forever.

## Decision

Adopt the protoncore_android pattern — in-app WebView with a JS bridge
that extracts the verification token, persists `{tokenType, tokenCode}`
into `SecretStore`, and lets `HumanVerificationHeadersInterceptor` attach
`x-pm-human-verification-token{,-type}` to **every** subsequent OkHttp
request. Close every 9001-swallowing call site (login, refresh, sync,
write engine) so the exception propagates uniformly to the UI layer.

## Alternatives considered

- **Stay with Chrome Custom Tabs and just hope cookies sync.** Rejected:
  Custom Tabs explicitly run in a separate process with their own cookie
  jar; the assumption was never valid.
- **Trampoline the captcha page through a deep-link redirect.** Rejected:
  requires a server-side redirect we don't control and a custom URL scheme
  registration that we'd rather not maintain. Proton Android made the same
  call: WebView, not deep link.
- **Per-call-site `catch HumanVerificationRequiredException` blocks only,
  keep Custom Tabs.** Rejected: closes the silent demotion but leaves the
  user with no way to actually solve the captcha — the next attempt loops.
- **Move 9001 handling up to a Retrofit-level `ApiErrorHandler` chain
  (Proton's exact pattern).** Rejected for v1: requires significant
  rework of the Retrofit/Call layer. Same end-state achievable via OkHttp
  interceptor + per-call-site rethrow with less churn.

## Consequences

What this makes easier:

- Every 9001 across login, refresh, and sync surfaces the captcha exactly
  once instead of looping, quarantining, or silently demoting.
- The captcha actually solves itself — the in-app WebView extracts the
  token via JS bridge so subsequent requests succeed.
- Mirrors Proton's official Android implementation, which de-risks
  protocol-shape changes the upstream team makes.

What this makes harder / new obligations:

- We now own a WebView with `setJavaScriptEnabled(true)`. Per ADR-0012's
  "no JS engine for protocol work", this is bounded to UI: the captcha
  widget runs Proton's hosted JS, never our own crypto code, never
  vCard/SRP/bcrypt logic.
- WebView host gating: navigation is restricted to `*.proton.me` via a
  `WebViewClient.shouldOverrideUrlLoading` guard — if the hosted page ever
  redirects elsewhere, the navigation is refused. This is the only host
  guard between our WebView and an attacker, and it's our responsibility
  to keep the allowlist correct.
- The HV token is persisted in `EncryptedSharedPreferences` alongside
  `accessToken`. Same threat profile (session-scoped, no KEK wrap),
  cleared on `SecretStore.logout()` and on the next 9001 with stale-token
  detection. THREAT_MODEL.md updated to list it as a session asset.
- The `androidx.browser` (Chrome Custom Tabs) dependency could be dropped
  if nothing else uses it — left in place for now since the cost is small.
- The JS-bridge envelope shape (`{type: "HUMAN_VERIFICATION_SUCCESS",
  payload: {token, type}}`) is `[U]` — inferred from
  protoncore_android's HV3DialogFragment. If Proton renames a field
  upstream we silently fail and the user sees the same captcha twice.

## Validation

- Unit tests in `:core:proton-api` cover header attachment, stale-token
  detection, and the empty-source default.
- Unit tests in `:core:sync` cover HV propagation through `fetchInfo`,
  `submitAuthAndVerifyProof`, `submitTwoFactorCode`, and `TokenRefresher`.
- Manual verification requires an account that organically triggers 9001
  from our test IP — the existing test account
  (`pc0ntact@proton.me` per `.env`) did not. Validation deferred until a
  triggering account or a server-side hook is available.
- Cross-reference: `protoncore_android/human-verification/.../HV3DialogFragment.kt`
  remains the upstream pattern; if Proton significantly rewrites it we
  should reread it.
