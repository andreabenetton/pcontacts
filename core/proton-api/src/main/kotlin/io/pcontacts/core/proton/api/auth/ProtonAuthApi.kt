// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.proton.api.auth

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.POST

/**
 * Proton auth endpoints — confirmed against
 * `packages/shared/lib/api/auth.ts` at the pinned WebClients commit.
 * Path strings and HTTP verbs are not parameterized; mistyping any of
 * them is an instant production break.
 */
interface ProtonAuthApi {

    @POST("core/v4/auth/info")
    suspend fun getInfo(@Body request: InfoRequest): InfoResponse

    @POST("core/v4/auth")
    suspend fun auth(@Body request: AuthRequest): AuthResponse

    @POST("core/v4/auth/2fa")
    suspend fun auth2FA(@Body request: TwoFactorRequest): TwoFactorResponse

    @POST("auth/refresh")
    suspend fun refresh(@Body request: RefreshRequest): RefreshResponse

    @DELETE("core/v4/auth")
    suspend fun revoke()
}
