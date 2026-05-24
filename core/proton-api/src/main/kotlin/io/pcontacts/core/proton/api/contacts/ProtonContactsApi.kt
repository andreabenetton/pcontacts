// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.proton.api.contacts

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Proton contacts endpoints — paths confirmed against
 * `packages/shared/lib/api/contacts.ts`.
 *
 * Filters: per the web client `Email` and `LabelID` are XOR — pass at
 * most one. Passing both returns HTTP 400 [V] (validated 2026-05-24);
 * the pager never does both.
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

    /**
     * Cheap per-contact metadata listing — no Cards[]. The sync engine
     * uses this to enumerate ContactIDs and compare server-side
     * `ModifyTime` against the locally stored `modify_time` before
     * deciding to fetch + decrypt the full contact.
     */
    @GET("contacts/v4/contacts")
    suspend fun listContacts(
        @Query("Page") page: Int,
        @Query("PageSize") pageSize: Int,
        @Query("LabelID") labelIdFilter: String? = null
    ): ContactsPageResponse

    // --- Write endpoints (ADR-0017 / ADR-0018, phase 9) ---

    /**
     * Creates one or more contacts. Each element in the request's
     * `contacts` list is one contact's full card set.
     * [V] packages/shared/lib/api/contacts.ts `addContacts`.
     */
    @POST("contacts/v4/contacts")
    suspend fun createContacts(@Body request: CreateContactsRequest): CreateContactsResponse

    /**
     * Replaces the entire Cards[] array for one contact. The server
     * returns the updated contact with a new `ModifyTime`.
     * [V] packages/shared/lib/api/contacts.ts `updateContact`.
     */
    @PUT("contacts/v4/contacts/{id}")
    suspend fun updateContact(
        @Path("id") id: String,
        @Body request: UpdateContactRequest
    ): UpdateContactResponse

    /**
     * Bulk-deletes contacts by ID. Note: Proton uses PUT, not HTTP
     * DELETE, for this endpoint.
     * [V] packages/shared/lib/api/contacts.ts `deleteContacts`.
     */
    @PUT("contacts/v4/contacts/delete")
    suspend fun deleteContacts(@Body request: BulkDeleteRequest): BulkDeleteResponse
}
