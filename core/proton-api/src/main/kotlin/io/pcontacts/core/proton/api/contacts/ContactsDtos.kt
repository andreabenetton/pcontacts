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
 *   [A] response envelope shape (`{Code, ContactEmails, Total}`) inferred
 *       from web-client consumption; first integration against a live
 *       account confirms.
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
