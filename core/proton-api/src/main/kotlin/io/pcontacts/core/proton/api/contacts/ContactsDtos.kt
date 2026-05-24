// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.proton.api.contacts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire-level shapes for the Proton contacts endpoints. Field names match
 * the JSON exactly; capitalization matters.
 *
 * Verification status:
 *   [V] field names + endpoint paths confirmed in
 *       ProtonMail/WebClients packages/shared/lib/api/contacts.ts and
 *       packages/shared/lib/interfaces/contacts/ContactApi.ts
 *   [V] response envelope shape (`{Code, ContactEmails, Total}`) validated
 *       against live Proton API (2026-05-24).
 */

@Serializable
data class ContactEmailDto(
    @SerialName("ID") val id: String,
    @SerialName("Email") val email: String,
    @SerialName("Name") val name: String = "",
    @SerialName("Type") val type: List<String> = emptyList(),
    @SerialName("Defaults") val defaults: Int = 0,
    @SerialName("Order") val order: Int = 0,
    @SerialName("ContactID") val contactId: String,
    @SerialName("LabelIDs") val labelIds: List<String> = emptyList(),
    @SerialName("LastUsedTime") val lastUsedTime: Long = 0L
)

@Serializable
data class ContactEmailsPageResponse(
    @SerialName("Code") val code: Int = 0,
    @SerialName("ContactEmails") val contactEmails: List<ContactEmailDto> = emptyList(),
    @SerialName("Total") val total: Int = 0
)

/**
 * One element of the `Cards[]` array on a full Contact response.
 *
 * `Type` semantics ([V] from
 * packages/shared/lib/contacts/constants.ts CONTACT_CARD_TYPE):
 *   0 = CLEAR_TEXT            — Data is plaintext, no Signature.
 *   1 = ENCRYPTED             — Data is an ASCII-armored OpenPGP message; no Signature.
 *   2 = SIGNED                — Data is plaintext; Signature is a detached OpenPGP signature.
 *   3 = ENCRYPTED_AND_SIGNED  — Data is OpenPGP message; Signature is detached signature over the plaintext.
 *
 * Signature is null exactly for Type 0 and Type 1; a SIGNED or
 * ENCRYPTED_AND_SIGNED card with `Signature == null` is server-side
 * malformed and the decrypter rejects it.
 */
@Serializable
data class ContactCardDto(
    @SerialName("Type") val type: Int,
    @SerialName("Data") val data: String,
    @SerialName("Signature") val signature: String? = null
)

/**
 * Full Contact payload returned by `GET contacts/v4/contacts/{id}`.
 * The fields beyond `ID` + `Cards` are denormalised metadata also
 * available via the listing endpoint; we keep them here so the sync
 * engine can populate `contact_map.modify_time` without a second
 * round-trip. [V] envelope validated against live Proton API (2026-05-24).
 */
@Serializable
data class ContactDto(
    @SerialName("ID") val id: String,
    @SerialName("Name") val name: String = "",
    @SerialName("UID") val uid: String = "",
    @SerialName("Size") val size: Long = 0L,
    @SerialName("CreateTime") val createTime: Long = 0L,
    @SerialName("ModifyTime") val modifyTime: Long = 0L,
    @SerialName("Cards") val cards: List<ContactCardDto> = emptyList(),
    @SerialName("ContactEmails") val contactEmails: List<ContactEmailDto> = emptyList(),
    @SerialName("LabelIDs") val labelIds: List<String> = emptyList()
)

@Serializable
data class GetContactResponse(
    @SerialName("Code") val code: Int = 0,
    @SerialName("Contact") val contact: ContactDto
)

/**
 * Per-contact metadata row from `GET contacts/v4/contacts` (the
 * cheap listing endpoint — no Cards[]). The sync engine uses
 * `modifyTime` to cheap-skip unchanged contacts so it doesn't have
 * to fetch + decrypt every contact on every run.
 *
 * Mirrors `ContactMetadata` from the web client — only the fields
 * the engine actually needs are modeled; everything else is
 * tolerated via `ignoreUnknownKeys`.
 */
@Serializable
data class ContactMetadataDto(
    @SerialName("ID") val id: String,
    @SerialName("Name") val name: String = "",
    @SerialName("UID") val uid: String = "",
    @SerialName("Size") val size: Long = 0L,
    @SerialName("CreateTime") val createTime: Long = 0L,
    @SerialName("ModifyTime") val modifyTime: Long = 0L,
    @SerialName("LabelIDs") val labelIds: List<String> = emptyList()
)

@Serializable
data class ContactsPageResponse(
    @SerialName("Code") val code: Int = 0,
    @SerialName("Contacts") val contacts: List<ContactMetadataDto> = emptyList(),
    @SerialName("Total") val total: Int = 0
)

// --- Write-path DTOs (ADR-0017 / ADR-0018, phase 9) ---

/**
 * One contact's card set within a `POST /contacts/v4/contacts` batch.
 * [V] packages/shared/lib/api/contacts.ts `addContacts`.
 */
@Serializable
data class ContactCardBundle(
    @SerialName("Cards") val cards: List<ContactCardDto>
)

/**
 * Request body for `POST /contacts/v4/contacts`. Creates one or more
 * contacts in a single call. Each element in `contacts` is one
 * contact's full card set.
 * [V] packages/shared/lib/api/contacts.ts `addContacts`.
 */
@Serializable
data class CreateContactsRequest(
    @SerialName("Contacts") val contacts: List<ContactCardBundle>,
    @SerialName("Overwrite") val overwrite: Int = 0,
    @SerialName("Labels") val labels: Int = 0
)

@Serializable
data class CreateContactResponseBody(
    @SerialName("Code") val code: Int = 0,
    @SerialName("Contact") val contact: ContactDto? = null
)

@Serializable
data class CreateContactResponseItem(
    @SerialName("Index") val index: Int = 0,
    @SerialName("Response") val response: CreateContactResponseBody
)

/**
 * Response envelope for `POST /contacts/v4/contacts`.
 * [V] packages/shared/lib/api/contacts.ts — per-contact sub-responses.
 */
@Serializable
data class CreateContactsResponse(
    @SerialName("Code") val code: Int = 0,
    @SerialName("Responses") val responses: List<CreateContactResponseItem> = emptyList()
)

/**
 * Request body for `PUT /contacts/v4/contacts/{id}`. Replaces the
 * entire Cards[] array for one contact.
 * [V] packages/shared/lib/api/contacts.ts `updateContact`.
 */
@Serializable
data class UpdateContactRequest(
    @SerialName("Cards") val cards: List<ContactCardDto>
)

@Serializable
data class UpdateContactResponse(
    @SerialName("Code") val code: Int = 0,
    @SerialName("Contact") val contact: ContactDto? = null
)

/**
 * Request body for `PUT /contacts/v4/contacts/delete` (bulk delete).
 * Note: Proton uses PUT, not DELETE, for this endpoint.
 * [V] packages/shared/lib/api/contacts.ts `deleteContacts`.
 */
@Serializable
data class BulkDeleteRequest(
    @SerialName("IDs") val ids: List<String>
)

@Serializable
data class DeleteResponseBody(
    @SerialName("Code") val code: Int = 0
)

@Serializable
data class DeleteResponseItem(
    @SerialName("ID") val id: String,
    @SerialName("Response") val response: DeleteResponseBody
)

@Serializable
data class BulkDeleteResponse(
    @SerialName("Code") val code: Int = 0,
    @SerialName("Responses") val responses: List<DeleteResponseItem> = emptyList()
)
