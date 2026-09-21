// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.contactswriter

import android.accounts.Account
import android.content.ContentProviderClient

/**
 * Appends user-selected [LinkedField]s to a Proton RawContact
 * (ADR-0023). Additive only: no existing Data row is touched, and the
 * batch never addresses any other RawContact. The RawContact is marked
 * DIRTY in the same batch so the next `detectChanges()` pass queues
 * the UPDATE that carries the fields to Proton.
 */
class LinkedFieldsWriter(private val provider: ContentProviderClient) {

    fun append(account: Account, rawContactId: Long, fields: List<LinkedField>) {
        if (fields.isEmpty()) return
        val ops = ContactsContractOps.buildAppend(account, rawContactId, fields)
        provider.applyBatch(ArrayList(ops))
    }
}
