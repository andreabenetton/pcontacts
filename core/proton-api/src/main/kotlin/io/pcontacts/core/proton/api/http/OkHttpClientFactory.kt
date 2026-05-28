// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.proton.api.http

import io.pcontacts.core.proton.api.ProtonApiConfig
import io.pcontacts.core.proton.api.Session
import java.util.concurrent.TimeUnit
import okhttp3.Authenticator
import okhttp3.OkHttpClient

/**
 * The single `OkHttpClient` factory the rest of the app uses. Per
 * ADR-0015 there must be exactly one OkHttpClient in the codebase,
 * constructed here — that's how we enforce "no request leaves
 * `:core:proton-api` for hosts other than `*.proton.me`".
 *
 * Layers applied to every request:
 *   - HeadersInterceptor   — `accept`, `x-pm-appversion`,
 *                            conditional `x-pm-uid`+`Authorization`
 *   - AuthInterceptor      — attaches the live Session's tokens
 *   - ProtonHostDnsGuard   — refuses DNS for hosts outside *.proton.me
 *                            (localhost allowed for MockWebServer tests)
 *   - CertificatePinner    — SPKI pins from
 *                            resources/proton_certificate_pins.txt
 *                            (ISRG Root X1 + X2; release gated)
 *   - Authenticator (opt)  — RefreshingAuthenticator for 401 →
 *                            /auth/refresh → retry; null on the
 *                            refresh-only stage to avoid recursion.
 *   - 429 backoff (net)    — FibonacciBackoffInterceptor retries
 *                            rate-limited requests on the wire side
 *                            so callers don't see 429s under normal
 *                            transient load.
 *   - AppVersionRejection  — peeks the JSON body for Code 5003/5004
 *                            and throws AppVersionRejectedException
 *                            so callers can distinguish "app needs
 *                            update" from generic failures.
 *
 * Logging interceptor is intentionally absent. If one is added it must
 * use `:core:logging`'s `Logger` and never emit request/response bodies
 * for `/auth*` or `/contacts/v4/contacts*` paths.
 */
object OkHttpClientFactory {

    fun create(
        config: ProtonApiConfig,
        session: Session,
        authenticator: Authenticator? = null,
        humanVerificationTokens: HumanVerificationTokenSource = HumanVerificationTokenSource.Empty
    ): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .addInterceptor(HeadersInterceptor(config))
            // HV-token headers attach to every request when a captcha has
            // been solved this session. Runs immediately after the static
            // header layer so AuthInterceptor still has a chance to set
            // x-pm-uid/Authorization on top.
            .addInterceptor(HumanVerificationHeadersInterceptor(humanVerificationTokens))
            .addInterceptor(AuthInterceptor(session))
            // Application-layer (not network) — network interceptors must
            // proceed() exactly once. The request is stamped with headers
            // + auth by the time backoff sees it, so retries replay the
            // same authenticated request without re-stamping.
            .addInterceptor(FibonacciBackoffInterceptor())
            // Human-verification (9001) detection — peeks the JSON body
            // and throws HumanVerificationRequiredException so 9001 is
            // never silently auto-retried. Receives the same token source
            // so it can clear stale tokens on a fresh 9001.
            .addInterceptor(HumanVerificationInterceptor(tokens = humanVerificationTokens))
            // App-version rejection (5003/5004) — must run after 9001
            // so the more-specific human-verification is caught first.
            .addInterceptor(AppVersionRejectionInterceptor())
            .dns(ProtonHostDnsGuard())
            .certificatePinner(ProtonCertificatePins.buildPinner())
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
        if (authenticator != null) builder.authenticator(authenticator)
        return builder.build()
    }
}
