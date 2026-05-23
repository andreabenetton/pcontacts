// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.proton.api.contacts

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Walks every page of `contacts/v4/contacts` (the cheap metadata
 * endpoint) and emits each `ContactMetadataDto` exactly once. Cold
 * Flow — restarting the collection starts a fresh server-side scan.
 *
 * Mirrors `ContactEmailsPager` in semantics and stop conditions:
 *   - Pages are 0-indexed.
 *   - Stop when the server returns fewer items than the requested
 *     pageSize, OR an empty page outright.
 *   - `Total` is intentionally ignored — it can lag mid-pagination
 *     when contacts are added or removed during the walk.
 */
class ContactsMetadataPager(
    private val api: ProtonContactsApi,
    private val pageSize: Int = DEFAULT_PAGE_SIZE
) {
    init {
        require(pageSize in 1..MAX_PAGE_SIZE) { "pageSize out of range: $pageSize" }
    }

    fun metadata(labelIdFilter: String? = null): Flow<ContactMetadataDto> = flow {
        var page = 0
        while (true) {
            val response = api.listContacts(
                page = page,
                pageSize = pageSize,
                labelIdFilter = labelIdFilter
            )
            response.contacts.forEach { emit(it) }
            if (response.contacts.size < pageSize) return@flow
            page += 1
        }
    }

    companion object {
        const val DEFAULT_PAGE_SIZE = 1000
        const val MAX_PAGE_SIZE = 1000
    }
}
