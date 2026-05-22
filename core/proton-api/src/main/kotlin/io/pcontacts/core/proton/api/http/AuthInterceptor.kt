// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.proton.api.http

import io.pcontacts.core.proton.api.Session
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Attaches `x-pm-uid` and `Authorization: Bearer <AccessToken>` when the
 * session has them. Unauthenticated endpoints (e.g. `core/v4/auth/info`)
 * are reached before `Session` is populated and travel without these
 * headers, matching the Proton web client behavior.
 *
 * The 401 → refresh dance is handled by a separate `Authenticator` (the
 * OkHttp Authenticator API, not this Interceptor), implemented in a
 * follow-up commit.
 */
class AuthInterceptor(
    private val session: Session
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val uid = session.uid()
        val token = session.accessToken()
        if (uid == null || token == null) {
            return chain.proceed(original)
        }
        val authed = original.newBuilder()
            .header("x-pm-uid", uid)
            .header("Authorization", "Bearer $token")
            .build()
        return chain.proceed(authed)
    }
}
