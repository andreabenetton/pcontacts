# ADR-0012: HTTP stack — OkHttp + Retrofit, single-flight refresh, Fibonacci backoff

- **Status:** Accepted
- **Date:** 2026-05-22
- **Deciders:** project owner
- **Related:** ADR-0014

## Context

The Proton API requires a non-trivial HTTP pipeline:

1. **Default headers** on every request: `accept: application/vnd.protonmail.v1+json` `[V]`, `x-pm-appversion: <client>@<semver>` `[A]`, `x-pm-uid` (after login) `[V]`, `Authorization: Bearer <AccessToken>` (after login) `[V]`, optional `x-pm-locale`.
2. **401 → single-flight refresh.** The web client (`packages/shared/lib/api/helpers/refreshHandlers.ts`) ensures only one refresh fires per UID despite concurrent failures, with a 15-second cross-tab lock and ~50 ms post-refresh delay. We mirror this on Android: a mutex around `POST auth/refresh`, retry the original request once on success, surface a re-auth UI on failure.
3. **429 → Fibonacci backoff** honoring `Retry-After`. `auth` endpoint suppresses 429 handling (`ignoreHandler`) — we mirror.
4. **9001 (human verification challenge).** Surface to UI; never auto-loop. Append `x-pm-human-verification-token` + `*-type` headers on the replayed request after the user solves the challenge.
5. **Certificate pinning** (ADR-0014 covers the modulus signing key; this ADR covers SPKI pinning of the api.proton.me TLS chain).

The library options:

- **OkHttp + Retrofit + kotlinx.serialization** — the canonical Android stack. Interceptor model handles the above patterns directly.
- **Ktor client** — pure Kotlin, multiplatform. Solid but slightly less mature interceptor primitives on Android and less ecosystem tooling (e.g. `MockWebServer` story is weaker).

We pick OkHttp + Retrofit because the interceptor + `EventListener` model matches the web client's request-lifecycle hooks closely, and `MockWebServer` is the best-in-class HTTP fixture tool.

## Decision

Use **OkHttp 4.x + Retrofit 2.x + kotlinx.serialization** in `:core:proton-api`.

**Single `OkHttpClient` instance** (per `Account`), built with:

```
.addInterceptor(HeaderInterceptor)         // accept, x-pm-appversion, x-pm-locale
.addInterceptor(AuthInterceptor)           // x-pm-uid, Authorization (when present)
.addInterceptor(HumanVerificationInterceptor) // x-pm-human-verification-* (when present)
.addInterceptor(LoggingInterceptor.redacting()) // bodies elided on /auth and /contacts/*
.addNetworkInterceptor(RetryInterceptor)   // 429 Fibonacci backoff
.authenticator(RefreshAuthenticator)       // 401 → single-flight refresh
.certificatePinner(ProtonCertPinner)       // SPKI pins for api.proton.me
.connectTimeout(15, SECONDS)
.readTimeout(30, SECONDS)
.callTimeout(60, SECONDS)
.build()
```

**`RefreshAuthenticator`:**

- Uses a per-UID `Mutex` so concurrent 401s collapse to one refresh.
- On `POST auth/refresh` success: update tokens via `SecretStore`, sleep 50 ms (matches web client's cookie-propagation pause), return new `Request` with the rotated `Authorization` header.
- On refresh failure: clear session, surface a re-auth signal via a `MutableSharedFlow<AuthEvent>` collected by `:feature:onboarding`.

**`RetryInterceptor` (429):**

- Honor `Retry-After` header if present.
- Otherwise, Fibonacci backoff sequence (1s, 1s, 2s, 3s, 5s, 8s, 13s; cap at 30s) up to 7 attempts.
- Skip retry for the `/auth` endpoint family (matches `ignoreHandler` in `refreshHandlers.ts`).

**`HumanVerificationInterceptor`:**

- Detects `9001` JSON error code in response body.
- Surfaces a `HumanVerificationChallenge` event to the UI (with the embedded HV URL / method / token).
- Does **not** auto-replay; waits for the UI to call `submitChallengeToken(token, type)` then attaches headers on the next request.

**`LoggingInterceptor` redaction:**

- Body logging entirely disabled for paths matching `/auth*`, `/contacts/v4/contacts*`, `/users`, `/keys*`.
- Header logging strips `Authorization`, `x-pm-uid`, `x-pm-human-verification-token`.
- Disabled by default in release builds (controlled by `BuildConfig.HTTP_LOGS`).

**Retrofit service interfaces** are Kotlin `interface` with `suspend fun` methods returning the response DTO directly. Errors throw `ProtonApiException` with code + class + URL, never with body content.

## Alternatives considered

- **Ktor client.** Rejected — same capability, weaker testing story for our use case.
- **Roll a thin HTTP client over `HttpURLConnection`.** Rejected — would re-implement Retry/Refresh/Auth/Pinning poorly.
- **No interceptors; do it all in Retrofit converters.** Rejected — wrong layer; converters don't see retries.

## Consequences

- The HTTP layer is a single `:core:proton-api` module with a clean test surface (MockWebServer + recorded JSON fixtures).
- Every authenticated request goes through both `AuthInterceptor` and `RefreshAuthenticator` — token rotation is transparent to callers.
- 9001 challenges always require a user-in-the-loop. Periodic background sync that hits a challenge will fail this run and surface a notification asking the user to open the app and solve the challenge.
- Certificate pins are baked into the build (BuildConfig). Pin rotation requires an app update; we ship two pins (current + backup) to avoid bricking on rotation.

## Validation

- MockWebServer test: 401 response triggers one `auth/refresh` then retries the original request once. Two concurrent 401s trigger exactly one refresh.
- MockWebServer test: 429 with `Retry-After: 5` waits ~5 s and retries; without `Retry-After` it follows the Fibonacci sequence.
- MockWebServer test: 9001 surfaces a `HumanVerificationChallenge` event and does not auto-replay.
- Log scrape test: under `assembleDebug`, run a sync against MockWebServer, capture `Log.*` output, assert no `Bearer`/`Password`/`Signature`/JSON-body strings appear.
- Pinning test: a MockWebServer with a wrong leaf SPKI is rejected at the TLS layer.
