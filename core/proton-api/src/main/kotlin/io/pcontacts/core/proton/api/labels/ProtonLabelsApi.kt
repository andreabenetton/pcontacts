// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.proton.api.labels

import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Labels endpoint. Per Plan §2.4 the labels endpoint surfaces
 * mail labels, mail folders, and contact groups via the `Type`
 * query parameter (see `LabelType` constants).
 *
 * The sync engine calls `listLabels(LabelType.CONTACT_GROUP)`
 * at the start of each run to resolve `ContactMetadataDto.labelIds`
 * to user-facing names + manage the local `ContactsContract.Groups`
 * rows under our account.
 */
interface ProtonLabelsApi {

    @GET("core/v4/labels")
    suspend fun listLabels(
        @Query("Type") type: Int
    ): GetLabelsResponse
}
