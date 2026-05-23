// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.proton.api.http

import io.pcontacts.core.proton.api.ProtonApiConfig
import io.pcontacts.core.proton.api.Session
import java.util.concurrent.TimeUnit
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
 *
 * Logging interceptor is intentionally absent. A debug-only redacting
 * logging interceptor lands in a follow-up commit; it will use
 * `:core:logging`'s `Logger` and never emit request/response bodies for
 * `/auth*` or `/contacts/v4/contacts*` paths.
 */
object OkHttpClientFactory {

    fun create(
        config: ProtonApiConfig,
        session: Session
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(HeadersInterceptor(config))
        .addInterceptor(AuthInterceptor(session))
        .dns(ProtonHostDnsGuard())
        .certificatePinner(ProtonCertificatePins.buildPinner())
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()
}
