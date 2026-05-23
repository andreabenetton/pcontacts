// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.proton.api.contacts

import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Proton contacts endpoints — paths confirmed against
 * `packages/shared/lib/api/contacts.ts`.
 *
 * Filters: per the web client `Email` and `LabelID` are XOR — pass at
 * most one. Passing both is undefined-server-behaviour [A]; the pager
 * never does both.
 */
interface ProtonContactsApi {

    @GET("contacts/v4/contacts/emails")
    suspend fun listContactEmails(
        @Query("Page") page: Int,
        @Query("PageSize") pageSize: Int,
        @Query("Email") emailFilter: String? = null,
        @Query("LabelID") labelIdFilter: String? = null
    ): ContactEmailsPageResponse

    /**
     * Fetches the full Cards[] payload for one contact. The sync engine
     * calls this once per ContactID flagged for update — never speculatively
     * for the entire contact list (full export endpoint is forbidden per
     * ADR-0007).
     */
    @GET("contacts/v4/contacts/{id}")
    suspend fun getContact(@Path("id") id: String): GetContactResponse
}
