// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.proton.api.users

import retrofit2.http.GET

/**
 * User + key-salt endpoints. Both require an authenticated session
 * (x-pm-uid + Authorization); the interceptor stack attaches those
 * automatically once SrpLoginOrchestrator has populated the Session.
 */
interface ProtonUsersApi {

    @GET("core/v4/users")
    suspend fun getUser(): GetUserResponse

    @GET("core/v4/keys/salts")
    suspend fun getKeySalts(): GetKeySaltsResponse
}
