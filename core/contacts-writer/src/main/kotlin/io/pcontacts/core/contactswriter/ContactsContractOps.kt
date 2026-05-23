// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.contactswriter

import android.accounts.Account
import android.content.ContentProviderOperation
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts

/**
 * Mechanical mapping from RawContactOpIntent → ContentProviderOperation
 * batches, applying the rules in ADR-0010:
 *   - every URI passes through SyncAdapterUri.decorate
 *   - update path is delete-then-reinsert child Data rows (RawContact
 *     itself is never deleted on update — preserves user-owned
 *     aggregate state like starred / ringtone)
 *   - inserts use withValueBackReference with absolute indices into
 *     the assembled batch — see `baseIdx` below
 *
 * `baseIdx` is the absolute index the FIRST op returned by this call
 * will occupy in the eventual batch. Back-references inside a Create
 * intent's Data rows must point at the RawContacts insert via this
 * absolute index. Get it wrong and Data rows attach to the wrong
 * RawContact — silently in production.
 *
 * BatchPlanner is responsible for setting `baseIdx` correctly and for
 * starting a new chunk (baseIdx=0) whenever adding the intent would
 * spill over the binder transaction limit.
 */
object ContactsContractOps {

    fun build(
        account: Account,
        intent: RawContactOpIntent,
        baseIdx: Int = 0
    ): List<ContentProviderOperation> =
        when (intent) {
            is RawContactOpIntent.CreateContact ->
                createContactOps(account, intent.row, baseIdx)
            is RawContactOpIntent.UpdateContact ->
                updateContactOps(account, intent.rawContactId, intent.row)
            is RawContactOpIntent.DeleteContact ->
                listOf(deleteContactOp(account, intent.sourceId))
        }

    private fun createContactOps(
        account: Account,
        row: ContactRow,
        baseIdx: Int
    ): List<ContentProviderOperation> {
        // 1 RawContacts insert + 1 StructuredName + N Email rows.
        val ops = ArrayList<ContentProviderOperation>(2 + row.emails.size)
        val rawIdx = baseIdx

        ops += ContentProviderOperation.newInsert(
            SyncAdapterUri.decorate(RawContacts.CONTENT_URI, account.name, account.type)
        )
            .withValue(RawContacts.ACCOUNT_NAME, account.name)
            .withValue(RawContacts.ACCOUNT_TYPE, account.type)
            .withValue(RawContacts.SOURCE_ID, row.sourceId)
            .build()

        ops += ContentProviderOperation.newInsert(Data.CONTENT_URI)
            .withValueBackReference(Data.RAW_CONTACT_ID, rawIdx)
            .withValue(Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE)
            .withValue(StructuredName.DISPLAY_NAME, row.displayName)
            .build()

        row.emails.forEachIndexed { idx, address ->
            ops += newEmailInsertWithBackRef(rawIdx, address, isPrimary = idx == 0)
        }
        return ops
    }

    private fun updateContactOps(
        account: Account,
        rawContactId: Long,
        row: ContactRow
    ): List<ContentProviderOperation> {
        val ops = ArrayList<ContentProviderOperation>(2 + row.emails.size)

        // 1) Wipe existing Data rows for this RawContact.
        ops += ContentProviderOperation.newDelete(
            SyncAdapterUri.decorate(Data.CONTENT_URI, account.name, account.type)
        )
            .withSelection("${Data.RAW_CONTACT_ID} = ?", arrayOf(rawContactId.toString()))
            .build()

        // 2) Re-insert with the known absolute RawContacts._ID — no back-refs.
        ops += ContentProviderOperation.newInsert(Data.CONTENT_URI)
            .withValue(Data.RAW_CONTACT_ID, rawContactId)
            .withValue(Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE)
            .withValue(StructuredName.DISPLAY_NAME, row.displayName)
            .build()

        row.emails.forEachIndexed { idx, address ->
            ops += newEmailInsertForExisting(rawContactId, address, isPrimary = idx == 0)
        }
        return ops
    }

    private fun newEmailInsertWithBackRef(
        rawIdx: Int,
        address: String,
        isPrimary: Boolean
    ): ContentProviderOperation =
        ContentProviderOperation.newInsert(Data.CONTENT_URI)
            .withValueBackReference(Data.RAW_CONTACT_ID, rawIdx)
            .withValue(Data.MIMETYPE, Email.CONTENT_ITEM_TYPE)
            .withValue(Email.ADDRESS, address)
            .withValue(Email.TYPE, Email.TYPE_OTHER)
            .apply {
                // Position 0 is the primary email; mark IS_PRIMARY +
                // IS_SUPER_PRIMARY so the system Contacts UI surfaces it
                // by default in "send email" affordances.
                if (isPrimary) {
                    withValue(Email.IS_PRIMARY, 1)
                    withValue(Email.IS_SUPER_PRIMARY, 1)
                }
            }
            .build()

    private fun newEmailInsertForExisting(
        rawContactId: Long,
        address: String,
        isPrimary: Boolean
    ): ContentProviderOperation =
        ContentProviderOperation.newInsert(Data.CONTENT_URI)
            .withValue(Data.RAW_CONTACT_ID, rawContactId)
            .withValue(Data.MIMETYPE, Email.CONTENT_ITEM_TYPE)
            .withValue(Email.ADDRESS, address)
            .withValue(Email.TYPE, Email.TYPE_OTHER)
            .apply {
                if (isPrimary) {
                    withValue(Email.IS_PRIMARY, 1)
                    withValue(Email.IS_SUPER_PRIMARY, 1)
                }
            }
            .build()

    private fun deleteContactOp(account: Account, sourceId: String): ContentProviderOperation =
        ContentProviderOperation.newDelete(
            SyncAdapterUri.decorate(RawContacts.CONTENT_URI, account.name, account.type)
        )
            .withSelection(
                "${RawContacts.SOURCE_ID} = ? AND ${RawContacts.ACCOUNT_TYPE} = ? AND ${RawContacts.ACCOUNT_NAME} = ?",
                arrayOf(sourceId, account.type, account.name)
            )
            .build()
}
