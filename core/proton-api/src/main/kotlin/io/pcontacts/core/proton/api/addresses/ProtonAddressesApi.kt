// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.proton.api.addresses

import retrofit2.http.GET

/**
 * Address-key fetch endpoint. Requires an authenticated session
 * (x-pm-uid + Authorization); the interceptor stack attaches both
 * once SrpLoginOrchestrator has populated the Session.
 *
 * The address keys returned here are unlocked downstream by the
 * decrypt path (see ContactDecryptBootstrap) using a two-level
 * Token decrypt under the user's primary key.
 */
interface ProtonAddressesApi {

    @GET("core/v4/addresses")
    suspend fun getAddresses(): GetAddressesResponse
}
