// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.contactswriter

import android.accounts.Account
import android.content.ContentProviderOperation
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Im
import android.provider.ContactsContract.CommonDataKinds.Note
import android.provider.ContactsContract.CommonDataKinds.Organization as CCOrganization
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.Photo
import android.provider.ContactsContract.CommonDataKinds.StructuredName as CCStructuredName
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
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
 * Per-contact op count (Create):
 *   1 RawContacts + 1 StructuredName
 *   + N Email + M Phone + K Address + L Note + I Im
 *   + (1 Organization?) + (1 Photo?).
 * Update adds 1 prepended Delete.
 *
 * Android type-class import aliasing:
 *   - `StructuredName` (data row constants) → CCStructuredName so our
 *     `ContactRow.StructuredName` data class keeps its short name.
 *   - `Organization` (data row constants) → CCOrganization for the
 *     same reason.
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
        val ops = ArrayList<ContentProviderOperation>(estimateOps(row))
        val rawIdx = baseIdx

        ops += ContentProviderOperation.newInsert(
            SyncAdapterUri.decorate(RawContacts.CONTENT_URI, account.name, account.type)
        )
            .withValue(RawContacts.ACCOUNT_NAME, account.name)
            .withValue(RawContacts.ACCOUNT_TYPE, account.type)
            .withValue(RawContacts.SOURCE_ID, row.sourceId)
            .build()

        ops += newStructuredNameInsertWithBackRef(rawIdx, row.displayName, row.structuredName)
        appendChildDataInsertsWithBackRef(ops, rawIdx, row)
        return ops
    }

    private fun updateContactOps(
        account: Account,
        rawContactId: Long,
        row: ContactRow
    ): List<ContentProviderOperation> {
        val ops = ArrayList<ContentProviderOperation>(estimateOps(row) + 1)

        // 1) Wipe existing Data rows for this RawContact.
        ops += ContentProviderOperation.newDelete(
            SyncAdapterUri.decorate(Data.CONTENT_URI, account.name, account.type)
        )
            .withSelection("${Data.RAW_CONTACT_ID} = ?", arrayOf(rawContactId.toString()))
            .build()

        // 2) Re-insert with the known absolute RawContacts._ID — no back-refs.
        ops += newStructuredNameInsertForExisting(rawContactId, row.displayName, row.structuredName)
        appendChildDataInsertsForExisting(ops, rawContactId, row)
        return ops
    }

    private fun appendChildDataInsertsWithBackRef(
        ops: ArrayList<ContentProviderOperation>,
        rawIdx: Int,
        row: ContactRow
    ) {
        row.emails.forEachIndexed { idx, address ->
            ops += newEmailInsertWithBackRef(rawIdx, address, isPrimary = idx == 0)
        }
        val phonePrimaryIdx = resolvePrimaryPhoneIndex(row.phones)
        row.phones.forEachIndexed { idx, phone ->
            ops += newPhoneInsertWithBackRef(rawIdx, phone, isPrimary = idx == phonePrimaryIdx)
        }
        val addressPrimaryIdx = resolvePrimaryAddressIndex(row.addresses)
        row.addresses.forEachIndexed { idx, addr ->
            ops += newPostalInsertWithBackRef(rawIdx, addr, isPrimary = idx == addressPrimaryIdx)
        }
        row.organization?.let { ops += newOrganizationInsertWithBackRef(rawIdx, it) }
        row.notes.forEach { note -> ops += newNoteInsertWithBackRef(rawIdx, note) }
        row.imAccounts.forEach { im -> ops += newImInsertWithBackRef(rawIdx, im) }
        row.photo?.let { photo ->
            val fitted = PhotoDownscaler.downscale(photo.data)
            if (fitted != null) ops += newPhotoInsertWithBackRef(rawIdx, ContactPhoto(fitted))
        }
    }

    private fun appendChildDataInsertsForExisting(
        ops: ArrayList<ContentProviderOperation>,
        rawContactId: Long,
        row: ContactRow
    ) {
        row.emails.forEachIndexed { idx, address ->
            ops += newEmailInsertForExisting(rawContactId, address, isPrimary = idx == 0)
        }
        val phonePrimaryIdx = resolvePrimaryPhoneIndex(row.phones)
        row.phones.forEachIndexed { idx, phone ->
            ops += newPhoneInsertForExisting(rawContactId, phone, isPrimary = idx == phonePrimaryIdx)
        }
        val addressPrimaryIdx = resolvePrimaryAddressIndex(row.addresses)
        row.addresses.forEachIndexed { idx, addr ->
            ops += newPostalInsertForExisting(rawContactId, addr, isPrimary = idx == addressPrimaryIdx)
        }
        row.organization?.let { ops += newOrganizationInsertForExisting(rawContactId, it) }
        row.notes.forEach { note -> ops += newNoteInsertForExisting(rawContactId, note) }
        row.imAccounts.forEach { im -> ops += newImInsertForExisting(rawContactId, im) }
        row.photo?.let { photo ->
            val fitted = PhotoDownscaler.downscale(photo.data)
            if (fitted != null) ops += newPhotoInsertForExisting(rawContactId, ContactPhoto(fitted))
        }
    }

    private fun estimateOps(row: ContactRow): Int =
        2 + row.emails.size + row.phones.size + row.addresses.size +
            row.notes.size + row.imAccounts.size +
            (if (row.organization != null) 1 else 0) +
            (if (row.photo != null) 1 else 0)

    private fun resolvePrimaryPhoneIndex(phones: List<PhoneEntry>): Int {
        val explicit = phones.indexOfFirst { it.isPrimary }
        return if (explicit >= 0) explicit else 0
    }

    private fun resolvePrimaryAddressIndex(addresses: List<PostalAddress>): Int {
        val explicit = addresses.indexOfFirst { it.isPrimary }
        return if (explicit >= 0) explicit else 0
    }

    // ---- StructuredName ----

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

    // ---- Email ----

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

    // ---- Phone ----

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

    // ---- StructuredPostal ----

    private fun newPostalInsertWithBackRef(
        rawIdx: Int,
        address: PostalAddress,
        isPrimary: Boolean
    ): ContentProviderOperation {
        val builder = ContentProviderOperation.newInsert(Data.CONTENT_URI)
            .withValueBackReference(Data.RAW_CONTACT_ID, rawIdx)
            .withValue(Data.MIMETYPE, StructuredPostal.CONTENT_ITEM_TYPE)
            .withValue(StructuredPostal.TYPE, PostalAddressTypeMapper.toAndroid(address.type))
        applyPostalPieces(builder, address, isPrimary)
        return builder.build()
    }

    private fun newPostalInsertForExisting(
        rawContactId: Long,
        address: PostalAddress,
        isPrimary: Boolean
    ): ContentProviderOperation {
        val builder = ContentProviderOperation.newInsert(Data.CONTENT_URI)
            .withValue(Data.RAW_CONTACT_ID, rawContactId)
            .withValue(Data.MIMETYPE, StructuredPostal.CONTENT_ITEM_TYPE)
            .withValue(StructuredPostal.TYPE, PostalAddressTypeMapper.toAndroid(address.type))
        applyPostalPieces(builder, address, isPrimary)
        return builder.build()
    }

    private fun applyPostalPieces(
        builder: ContentProviderOperation.Builder,
        address: PostalAddress,
        isPrimary: Boolean
    ) {
        address.street?.takeIf { it.isNotBlank() }
            ?.let { builder.withValue(StructuredPostal.STREET, it) }
        address.poBox?.takeIf { it.isNotBlank() }
            ?.let { builder.withValue(StructuredPostal.POBOX, it) }
        address.neighborhood?.takeIf { it.isNotBlank() }
            ?.let { builder.withValue(StructuredPostal.NEIGHBORHOOD, it) }
        address.city?.takeIf { it.isNotBlank() }
            ?.let { builder.withValue(StructuredPostal.CITY, it) }
        address.region?.takeIf { it.isNotBlank() }
            ?.let { builder.withValue(StructuredPostal.REGION, it) }
        address.postcode?.takeIf { it.isNotBlank() }
            ?.let { builder.withValue(StructuredPostal.POSTCODE, it) }
        address.country?.takeIf { it.isNotBlank() }
            ?.let { builder.withValue(StructuredPostal.COUNTRY, it) }
        if (isPrimary) {
            builder.withValue(StructuredPostal.IS_PRIMARY, 1)
            builder.withValue(StructuredPostal.IS_SUPER_PRIMARY, 1)
        }
    }

    // ---- Organization ----

    private fun newOrganizationInsertWithBackRef(
        rawIdx: Int,
        org: Organization
    ): ContentProviderOperation {
        val builder = ContentProviderOperation.newInsert(Data.CONTENT_URI)
            .withValueBackReference(Data.RAW_CONTACT_ID, rawIdx)
            .withValue(Data.MIMETYPE, CCOrganization.CONTENT_ITEM_TYPE)
        applyOrganizationPieces(builder, org)
        return builder.build()
    }

    private fun newOrganizationInsertForExisting(
        rawContactId: Long,
        org: Organization
    ): ContentProviderOperation {
        val builder = ContentProviderOperation.newInsert(Data.CONTENT_URI)
            .withValue(Data.RAW_CONTACT_ID, rawContactId)
            .withValue(Data.MIMETYPE, CCOrganization.CONTENT_ITEM_TYPE)
        applyOrganizationPieces(builder, org)
        return builder.build()
    }

    private fun applyOrganizationPieces(
        builder: ContentProviderOperation.Builder,
        org: Organization
    ) {
        org.company?.takeIf { it.isNotBlank() }
            ?.let { builder.withValue(CCOrganization.COMPANY, it) }
        org.department?.takeIf { it.isNotBlank() }
            ?.let { builder.withValue(CCOrganization.DEPARTMENT, it) }
        org.title?.takeIf { it.isNotBlank() }
            ?.let { builder.withValue(CCOrganization.TITLE, it) }
    }

    // ---- Note ----

    private fun newNoteInsertWithBackRef(rawIdx: Int, note: String): ContentProviderOperation =
        ContentProviderOperation.newInsert(Data.CONTENT_URI)
            .withValueBackReference(Data.RAW_CONTACT_ID, rawIdx)
            .withValue(Data.MIMETYPE, Note.CONTENT_ITEM_TYPE)
            .withValue(Note.NOTE, note)
            .build()

    private fun newNoteInsertForExisting(rawContactId: Long, note: String): ContentProviderOperation =
        ContentProviderOperation.newInsert(Data.CONTENT_URI)
            .withValue(Data.RAW_CONTACT_ID, rawContactId)
            .withValue(Data.MIMETYPE, Note.CONTENT_ITEM_TYPE)
            .withValue(Note.NOTE, note)
            .build()

    // ---- Im ----

    private fun newImInsertWithBackRef(rawIdx: Int, im: ImAccount): ContentProviderOperation {
        val builder = ContentProviderOperation.newInsert(Data.CONTENT_URI)
            .withValueBackReference(Data.RAW_CONTACT_ID, rawIdx)
            .withValue(Data.MIMETYPE, Im.CONTENT_ITEM_TYPE)
            .withValue(Im.DATA, im.handle)
            .withValue(Im.TYPE, ImProtocolMapper.typeToAndroid(im.type))
        applyImProtocolColumns(builder, im)
        return builder.build()
    }

    private fun newImInsertForExisting(
        rawContactId: Long,
        im: ImAccount
    ): ContentProviderOperation {
        val builder = ContentProviderOperation.newInsert(Data.CONTENT_URI)
            .withValue(Data.RAW_CONTACT_ID, rawContactId)
            .withValue(Data.MIMETYPE, Im.CONTENT_ITEM_TYPE)
            .withValue(Im.DATA, im.handle)
            .withValue(Im.TYPE, ImProtocolMapper.typeToAndroid(im.type))
        applyImProtocolColumns(builder, im)
        return builder.build()
    }

    private fun applyImProtocolColumns(
        builder: ContentProviderOperation.Builder,
        im: ImAccount
    ) {
        builder.withValue(Im.PROTOCOL, ImProtocolMapper.toAndroid(im.protocol))
        if (im.protocol == ImProtocol.CUSTOM) {
            // ContactsContract requires CUSTOM_PROTOCOL when PROTOCOL == CUSTOM;
            // fall back to a generic label if the caller didn't supply one.
            builder.withValue(Im.CUSTOM_PROTOCOL, im.customProtocol?.takeIf { it.isNotBlank() } ?: "im")
        }
    }

    // ---- Photo ----

    private fun newPhotoInsertWithBackRef(rawIdx: Int, photo: ContactPhoto): ContentProviderOperation =
        ContentProviderOperation.newInsert(Data.CONTENT_URI)
            .withValueBackReference(Data.RAW_CONTACT_ID, rawIdx)
            .withValue(Data.MIMETYPE, Photo.CONTENT_ITEM_TYPE)
            .withValue(Photo.PHOTO, photo.data)
            .build()

    private fun newPhotoInsertForExisting(
        rawContactId: Long,
        photo: ContactPhoto
    ): ContentProviderOperation =
        ContentProviderOperation.newInsert(Data.CONTENT_URI)
            .withValue(Data.RAW_CONTACT_ID, rawContactId)
            .withValue(Data.MIMETYPE, Photo.CONTENT_ITEM_TYPE)
            .withValue(Photo.PHOTO, photo.data)
            .build()

    // ---- Delete ----

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
