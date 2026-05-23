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
 *                            (empty in source control; see README)
 *   - Authenticator (opt)  — RefreshingAuthenticator for 401 →
 *                            /auth/refresh → retry; null on the
 *                            refresh-only stage to avoid recursion.
 *   - 429 backoff (net)    — FibonacciBackoffInterceptor retries
 *                            rate-limited requests on the wire side
 *                            so callers don't see 429s under normal
 *                            transient load.
 *
 * Logging interceptor is intentionally absent. A debug-only redacting
 * logging interceptor lands in a follow-up commit; it will use
 * `:core:logging`'s `Logger` and never emit request/response bodies for
 * `/auth*` or `/contacts/v4/contacts*` paths.
 */
object OkHttpClientFactory {

    fun create(
        config: ProtonApiConfig,
        session: Session,
        authenticator: Authenticator? = null
    ): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .addInterceptor(HeadersInterceptor(config))
            .addInterceptor(AuthInterceptor(session))
            // Application-layer (not network) — network interceptors must
            // proceed() exactly once. The request is stamped with headers
            // + auth by the time backoff sees it, so retries replay the
            // same authenticated request without re-stamping.
            .addInterceptor(FibonacciBackoffInterceptor())
            // Human-verification (9001) detection — peeks the JSON body
            // and throws HumanVerificationRequiredException so 9001 is
            // never silently auto-retried.
            .addInterceptor(HumanVerificationInterceptor())
            .dns(ProtonHostDnsGuard())
            .certificatePinner(ProtonCertificatePins.buildPinner())
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
        if (authenticator != null) builder.authenticator(authenticator)
        return builder.build()
    }
}
