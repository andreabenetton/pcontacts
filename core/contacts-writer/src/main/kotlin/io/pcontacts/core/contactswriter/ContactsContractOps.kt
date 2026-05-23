// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.contactswriter

import android.accounts.Account
import android.content.ContentProviderOperation
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName as CCStructuredName
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
 *
 * Per-contact op count: 1 RawContacts insert + 1 StructuredName +
 * N Email + M Phone (for Create; +1 Delete for Update's wipe). The
 * BatchPlanner cap of 450 ops/batch easily holds a typical contact;
 * pathological contacts (≥ ~440 phones / emails) trip the planner's
 * per-intent guard.
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
        val ops = ArrayList<ContentProviderOperation>(2 + row.emails.size + row.phones.size)
        val rawIdx = baseIdx

        ops += ContentProviderOperation.newInsert(
            SyncAdapterUri.decorate(RawContacts.CONTENT_URI, account.name, account.type)
        )
            .withValue(RawContacts.ACCOUNT_NAME, account.name)
            .withValue(RawContacts.ACCOUNT_TYPE, account.type)
            .withValue(RawContacts.SOURCE_ID, row.sourceId)
            .build()

        ops += newStructuredNameInsertWithBackRef(rawIdx, row.displayName, row.structuredName)

        row.emails.forEachIndexed { idx, address ->
            ops += newEmailInsertWithBackRef(rawIdx, address, isPrimary = idx == 0)
        }
        val phonePrimaryIdx = resolvePrimaryIndex(row.phones)
        row.phones.forEachIndexed { idx, phone ->
            ops += newPhoneInsertWithBackRef(rawIdx, phone, isPrimary = idx == phonePrimaryIdx)
        }
        return ops
    }

    private fun updateContactOps(
        account: Account,
        rawContactId: Long,
        row: ContactRow
    ): List<ContentProviderOperation> {
        val ops = ArrayList<ContentProviderOperation>(2 + row.emails.size + row.phones.size)

        // 1) Wipe existing Data rows for this RawContact.
        ops += ContentProviderOperation.newDelete(
            SyncAdapterUri.decorate(Data.CONTENT_URI, account.name, account.type)
        )
            .withSelection("${Data.RAW_CONTACT_ID} = ?", arrayOf(rawContactId.toString()))
            .build()

        // 2) Re-insert with the known absolute RawContacts._ID — no back-refs.
        ops += newStructuredNameInsertForExisting(rawContactId, row.displayName, row.structuredName)

        row.emails.forEachIndexed { idx, address ->
            ops += newEmailInsertForExisting(rawContactId, address, isPrimary = idx == 0)
        }
        val phonePrimaryIdx = resolvePrimaryIndex(row.phones)
        row.phones.forEachIndexed { idx, phone ->
            ops += newPhoneInsertForExisting(rawContactId, phone, isPrimary = idx == phonePrimaryIdx)
        }
        return ops
    }

    /**
     * Picks the index of the phone we'll mark IS_SUPER_PRIMARY. Rule:
     * if any phone has `isPrimary == true`, the first such wins;
     * otherwise position 0 is primary. Mirrors the email behaviour
     * but uses the explicit isPrimary flag because phones don't have
     * the same primary-first sort guarantee email rows do.
     */
    private fun resolvePrimaryIndex(phones: List<PhoneEntry>): Int {
        val explicit = phones.indexOfFirst { it.isPrimary }
        return if (explicit >= 0) explicit else 0
    }

    private fun newStructuredNameInsertWithBackRef(
        rawIdx: Int,
        displayName: String,
        structured: StructuredName?
    ): ContentProviderOperation {
        val builder = ContentProviderOperation.newInsert(Data.CONTENT_URI)
            .withValueBackReference(Data.RAW_CONTACT_ID, rawIdx)
            .withValue(Data.MIMETYPE, CCStructuredName.CONTENT_ITEM_TYPE)
            .withValue(CCStructuredName.DISPLAY_NAME, displayName)
        applyStructuredNamePieces(builder, structured)
        return builder.build()
    }

    private fun newStructuredNameInsertForExisting(
        rawContactId: Long,
        displayName: String,
        structured: StructuredName?
    ): ContentProviderOperation {
        val builder = ContentProviderOperation.newInsert(Data.CONTENT_URI)
            .withValue(Data.RAW_CONTACT_ID, rawContactId)
            .withValue(Data.MIMETYPE, CCStructuredName.CONTENT_ITEM_TYPE)
            .withValue(CCStructuredName.DISPLAY_NAME, displayName)
        applyStructuredNamePieces(builder, structured)
        return builder.build()
    }

    private fun applyStructuredNamePieces(
        builder: ContentProviderOperation.Builder,
        structured: StructuredName?
    ) {
        if (structured == null) return
        structured.given?.takeIf { it.isNotBlank() }
            ?.let { builder.withValue(CCStructuredName.GIVEN_NAME, it) }
        structured.family?.takeIf { it.isNotBlank() }
            ?.let { builder.withValue(CCStructuredName.FAMILY_NAME, it) }
        structured.middle?.takeIf { it.isNotBlank() }
            ?.let { builder.withValue(CCStructuredName.MIDDLE_NAME, it) }
        structured.prefix?.takeIf { it.isNotBlank() }
            ?.let { builder.withValue(CCStructuredName.PREFIX, it) }
        structured.suffix?.takeIf { it.isNotBlank() }
            ?.let { builder.withValue(CCStructuredName.SUFFIX, it) }
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

    private fun newPhoneInsertWithBackRef(
        rawIdx: Int,
        phone: PhoneEntry,
        isPrimary: Boolean
    ): ContentProviderOperation =
        ContentProviderOperation.newInsert(Data.CONTENT_URI)
            .withValueBackReference(Data.RAW_CONTACT_ID, rawIdx)
            .withValue(Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE)
            .withValue(Phone.NUMBER, phone.number)
            .withValue(Phone.TYPE, PhoneTypeMapper.toAndroid(phone.type))
            .apply {
                if (isPrimary) {
                    withValue(Phone.IS_PRIMARY, 1)
                    withValue(Phone.IS_SUPER_PRIMARY, 1)
                }
            }
            .build()

    private fun newPhoneInsertForExisting(
        rawContactId: Long,
        phone: PhoneEntry,
        isPrimary: Boolean
    ): ContentProviderOperation =
        ContentProviderOperation.newInsert(Data.CONTENT_URI)
            .withValue(Data.RAW_CONTACT_ID, rawContactId)
            .withValue(Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE)
            .withValue(Phone.NUMBER, phone.number)
            .withValue(Phone.TYPE, PhoneTypeMapper.toAndroid(phone.type))
            .apply {
                if (isPrimary) {
                    withValue(Phone.IS_PRIMARY, 1)
                    withValue(Phone.IS_SUPER_PRIMARY, 1)
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
