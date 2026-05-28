// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.proton.api.http

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Attaches `x-pm-human-verification-token` and
 * `x-pm-human-verification-token-type` to every outgoing request when
 * the [tokens] source has stored a verification result. Once a user
 * solves a captcha and the token is persisted, **every** subsequent
 * Proton API call carries these headers — `/auth`, `/auth/2fa`,
 * `/auth/refresh`, every `/contacts/v4` endpoint — until the token is cleared.
 *
 * `[V]` Behavior mirrors
 * `ProtonMail/protoncore_android/network/data/.../ProtonApiBackend.kt::prepareHeaders`:
 * the same two header names, the same "set on every request when present"
 * policy.
 *
 * Must run **before** the request leaves the OkHttp chain. Wired into
 * [OkHttpClientFactory] right after [HeadersInterceptor]. The pairing
 * interceptor that *detects* 9001 ([HumanVerificationInterceptor]) runs
 * later in the chain.
 */
class HumanVerificationHeadersInterceptor(
    private val tokens: HumanVerificationTokenSource
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val token = tokens.token()
        val type = tokens.tokenType()
        val request = if (!token.isNullOrBlank() && !type.isNullOrBlank()) {
            chain.request().newBuilder()
                .header("x-pm-human-verification-token", token)
                .header("x-pm-human-verification-token-type", type)
                .build()
        } else {
            chain.request()
        }
        return chain.proceed(request)
    }
}
