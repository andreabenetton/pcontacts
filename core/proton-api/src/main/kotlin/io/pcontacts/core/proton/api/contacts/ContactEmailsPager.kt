// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.proton.api.contacts

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Walks every page of `contacts/v4/contacts/emails` and emits each
 * `ContactEmailDto` exactly once. Cold Flow — restarting the collection
 * starts a fresh server-side scan.
 *
 * Pagination semantics:
 *   - Pages are 0-indexed (the web client uses 0-indexed Page; [V]).
 *   - Stop when the server returns fewer items than the requested
 *     pageSize, OR an empty page outright. Both signal the last page.
 *   - Total field is intentionally ignored — it can lag mid-pagination
 *     if a contact is added or removed while we walk.
 *
 * The constructor takes `pageSize = 1000` to mirror the web client's
 * default; tests usually shrink it to exercise the multi-page branch.
 */
class ContactEmailsPager(
    private val api: ProtonContactsApi,
    private val pageSize: Int = DEFAULT_PAGE_SIZE
) {
    init {
        require(pageSize in 1..MAX_PAGE_SIZE) { "pageSize out of range: $pageSize" }
    }

    fun emails(emailFilter: String? = null, labelIdFilter: String? = null): Flow<ContactEmailDto> = flow {
        require(emailFilter == null || labelIdFilter == null) {
            "Email and LabelID filters are mutually exclusive"
        }
        var page = 0
        while (true) {
            val response = api.listContactEmails(
                page = page,
                pageSize = pageSize,
                emailFilter = emailFilter,
                labelIdFilter = labelIdFilter
            )
            response.contactEmails.forEach { emit(it) }
            if (response.contactEmails.size < pageSize) return@flow
            page += 1
        }
    }

    companion object {
        const val DEFAULT_PAGE_SIZE = 1000
        const val MAX_PAGE_SIZE = 1000
    }
}
