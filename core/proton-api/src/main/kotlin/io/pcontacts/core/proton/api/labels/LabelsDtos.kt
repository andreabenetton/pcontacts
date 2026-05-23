// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.proton.api.labels

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire-level shapes for the Proton labels endpoint
 * (`GET core/v4/labels?Type=...`). Field names confirmed against
 * `packages/shared/lib/interfaces/Label.ts` [V]; some fields
 * (Color / Order / Sticky / Display / Notify / Expanded) are
 * intentionally omitted because the contact-groups path doesn't
 * surface them in ContactsContract — extend the DTO when a
 * downstream consumer needs them.
 */

@Serializable
data class LabelDto(
    @SerialName("ID") val id: String,
    @SerialName("Name") val name: String = "",
    @SerialName("ParentID") val parentId: String? = null,
    @SerialName("Type") val type: Int = 0,
    @SerialName("Path") val path: String? = null
)

@Serializable
data class GetLabelsResponse(
    @SerialName("Code") val code: Int = 0,
    @SerialName("Labels") val labels: List<LabelDto> = emptyList()
)

/**
 * Proton's label Type taxonomy. `[V]` from
 * packages/shared/lib/constants.ts LABEL_TYPE:
 *   1 = mail label
 *   2 = contact group
 *   3 = mail folder (system)
 *   4 = contact group (sub-tier)
 *
 * Only type 2 is relevant to ContactsContract.Groups for now.
 */
object LabelType {
    const val MAIL_LABEL = 1
    const val CONTACT_GROUP = 2
    const val MAIL_FOLDER = 3
    const val CONTACT_GROUP_SUB = 4
}
