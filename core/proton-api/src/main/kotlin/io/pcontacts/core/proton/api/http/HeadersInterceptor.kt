// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.proton.api.http

import io.pcontacts.core.proton.api.ProtonApiConfig
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Attaches Proton's mandatory request headers (ADR-0012):
 *   - accept: application/vnd.protonmail.v1+json   [V]
 *   - x-pm-appversion: <appVersion>                [V structurally, A on accepted values]
 *   - x-pm-locale: <locale>                        when configured
 *
 * Auth-specific headers (`x-pm-uid`, `Authorization`) are attached by
 * `AuthInterceptor`, which runs after this one.
 */
class HeadersInterceptor(
    private val config: ProtonApiConfig
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val builder = original.newBuilder()
            .header("accept", "application/vnd.protonmail.v1+json")
            .header("x-pm-appversion", config.appVersion)

        config.locale?.takeIf { it.isNotBlank() }?.let { builder.header("x-pm-locale", it) }

        return chain.proceed(builder.build())
    }
}
