// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.contactswriter

import android.accounts.Account
import android.content.ContentProviderOperation
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.GroupMembership
import android.provider.ContactsContract.CommonDataKinds.Im
import android.provider.ContactsContract.CommonDataKinds.Note
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.Photo
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
import android.provider.ContactsContract.CommonDataKinds.Organization as CCOrganization
import android.provider.ContactsContract.CommonDataKinds.StructuredName as CCStructuredName

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
        val dataUri = SyncAdapterUri.decorate(Data.CONTENT_URI, account.name, account.type)

        ops += ContentProviderOperation.newInsert(
            SyncAdapterUri.decorate(RawContacts.CONTENT_URI, account.name, account.type)
        )
            .withValue(RawContacts.ACCOUNT_NAME, account.name)
            .withValue(RawContacts.ACCOUNT_TYPE, account.type)
            .withValue(RawContacts.SOURCE_ID, row.sourceId)
            .build()

        if (hasNameContent(row)) {
            ops += newStructuredNameInsertWithBackRef(dataUri, rawIdx, row.displayName, row.structuredName)
        }
        appendChildDataInsertsWithBackRef(ops, dataUri, rawIdx, row)
        return ops
    }

    private fun updateContactOps(
        account: Account,
        rawContactId: Long,
        row: ContactRow
    ): List<ContentProviderOperation> {
        val ops = ArrayList<ContentProviderOperation>(estimateOps(row) + 1)
        val dataUri = SyncAdapterUri.decorate(Data.CONTENT_URI, account.name, account.type)

        // 1) Wipe existing Data rows for this RawContact.
        ops += ContentProviderOperation.newDelete(dataUri)
            .withSelection("${Data.RAW_CONTACT_ID} = ?", arrayOf(rawContactId.toString()))
            .build()

        // 2) Re-insert with the known absolute RawContacts._ID — no back-refs.
        if (hasNameContent(row)) {
            ops += newStructuredNameInsertForExisting(dataUri, rawContactId, row.displayName, row.structuredName)
        }
        appendChildDataInsertsForExisting(ops, dataUri, rawContactId, row)
        return ops
    }

    /**
     * StructuredName row is emitted only when Proton supplied a real name —
     * a non-blank displayName (typically `FN`) or any structured-name piece
     * (`N`'s given/family/...). For pure phone-only / email-only contacts
     * we omit the row entirely so Android's aggregator can adopt the name
     * from a peer RawContact (e.g. a local SIM / WhatsApp entry with the
     * same phone number) rather than overwriting it with a phone-string
     * fallback.
     */
    private fun hasNameContent(row: ContactRow): Boolean {
        if (!row.displayName.isNullOrBlank()) return true
        val s = row.structuredName ?: return false
        return !s.given.isNullOrBlank() || !s.family.isNullOrBlank() ||
            !s.middle.isNullOrBlank() || !s.prefix.isNullOrBlank() ||
            !s.suffix.isNullOrBlank()
    }

    private fun appendChildDataInsertsWithBackRef(
        ops: ArrayList<ContentProviderOperation>,
        dataUri: Uri,
        rawIdx: Int,
        row: ContactRow
    ) {
        row.emails.forEachIndexed { idx, address ->
            ops += newEmailInsertWithBackRef(dataUri, rawIdx, address, isPrimary = idx == 0)
        }
        val phonePrimaryIdx = resolvePrimaryPhoneIndex(row.phones)
        row.phones.forEachIndexed { idx, phone ->
            ops += newPhoneInsertWithBackRef(dataUri, rawIdx, phone, isPrimary = idx == phonePrimaryIdx)
        }
        val addressPrimaryIdx = resolvePrimaryAddressIndex(row.addresses)
        row.addresses.forEachIndexed { idx, addr ->
            ops += newPostalInsertWithBackRef(dataUri, rawIdx, addr, isPrimary = idx == addressPrimaryIdx)
        }
        row.organization?.let { ops += newOrganizationInsertWithBackRef(dataUri, rawIdx, it) }
        row.notes.forEach { note -> ops += newNoteInsertWithBackRef(dataUri, rawIdx, note) }
        row.imAccounts.forEach { im -> ops += newImInsertWithBackRef(dataUri, rawIdx, im) }
        row.photo?.let { photo ->
            val fitted = PhotoDownscaler.downscale(photo.data)
            if (fitted != null) ops += newPhotoInsertWithBackRef(dataUri, rawIdx, ContactPhoto(fitted))
        }
        row.groupRowIds.forEach { gid -> ops += newGroupMembershipInsertWithBackRef(dataUri, rawIdx, gid) }
    }

    private fun appendChildDataInsertsForExisting(
        ops: ArrayList<ContentProviderOperation>,
        dataUri: Uri,
        rawContactId: Long,
        row: ContactRow
    ) {
        row.emails.forEachIndexed { idx, address ->
            ops += newEmailInsertForExisting(dataUri, rawContactId, address, isPrimary = idx == 0)
        }
        val phonePrimaryIdx = resolvePrimaryPhoneIndex(row.phones)
        row.phones.forEachIndexed { idx, phone ->
            ops += newPhoneInsertForExisting(dataUri, rawContactId, phone, isPrimary = idx == phonePrimaryIdx)
        }
        val addressPrimaryIdx = resolvePrimaryAddressIndex(row.addresses)
        row.addresses.forEachIndexed { idx, addr ->
            ops += newPostalInsertForExisting(dataUri, rawContactId, addr, isPrimary = idx == addressPrimaryIdx)
        }
        row.organization?.let { ops += newOrganizationInsertForExisting(dataUri, rawContactId, it) }
        row.notes.forEach { note -> ops += newNoteInsertForExisting(dataUri, rawContactId, note) }
        row.imAccounts.forEach { im -> ops += newImInsertForExisting(dataUri, rawContactId, im) }
        row.photo?.let { photo ->
            val fitted = PhotoDownscaler.downscale(photo.data)
            if (fitted != null) ops += newPhotoInsertForExisting(dataUri, rawContactId, ContactPhoto(fitted))
        }
        row.groupRowIds.forEach { gid -> ops += newGroupMembershipInsertForExisting(dataUri, rawContactId, gid) }
    }

    private fun estimateOps(row: ContactRow): Int =
        2 + row.emails.size + row.phones.size + row.addresses.size +
            row.notes.size + row.imAccounts.size + row.groupRowIds.size +
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
        dataUri: Uri,
        rawIdx: Int,
        displayName: String?,
        structured: StructuredName?
    ): ContentProviderOperation {
        val builder = ContentProviderOperation.newInsert(dataUri)
            .withValueBackReference(Data.RAW_CONTACT_ID, rawIdx)
            .withValue(Data.MIMETYPE, CCStructuredName.CONTENT_ITEM_TYPE)
        displayName?.takeIf { it.isNotBlank() }
            ?.let { builder.withValue(CCStructuredName.DISPLAY_NAME, it) }
        applyStructuredNamePieces(builder, structured)
        return builder.build()
    }

    private fun newStructuredNameInsertForExisting(
        dataUri: Uri,
        rawContactId: Long,
        displayName: String?,
        structured: StructuredName?
    ): ContentProviderOperation {
        val builder = ContentProviderOperation.newInsert(dataUri)
            .withValue(Data.RAW_CONTACT_ID, rawContactId)
            .withValue(Data.MIMETYPE, CCStructuredName.CONTENT_ITEM_TYPE)
        displayName?.takeIf { it.isNotBlank() }
            ?.let { builder.withValue(CCStructuredName.DISPLAY_NAME, it) }
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
        dataUri: Uri,
        rawIdx: Int,
        address: String,
        isPrimary: Boolean
    ): ContentProviderOperation =
        ContentProviderOperation.newInsert(dataUri)
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
        dataUri: Uri,
        rawContactId: Long,
        address: String,
        isPrimary: Boolean
    ): ContentProviderOperation =
        ContentProviderOperation.newInsert(dataUri)
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
        dataUri: Uri,
        rawIdx: Int,
        phone: PhoneEntry,
        isPrimary: Boolean
    ): ContentProviderOperation =
        ContentProviderOperation.newInsert(dataUri)
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
        dataUri: Uri,
        rawContactId: Long,
        phone: PhoneEntry,
        isPrimary: Boolean
    ): ContentProviderOperation =
        ContentProviderOperation.newInsert(dataUri)
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
        dataUri: Uri,
        rawIdx: Int,
        address: PostalAddress,
        isPrimary: Boolean
    ): ContentProviderOperation {
        val builder = ContentProviderOperation.newInsert(dataUri)
            .withValueBackReference(Data.RAW_CONTACT_ID, rawIdx)
            .withValue(Data.MIMETYPE, StructuredPostal.CONTENT_ITEM_TYPE)
            .withValue(StructuredPostal.TYPE, PostalAddressTypeMapper.toAndroid(address.type))
        applyPostalPieces(builder, address, isPrimary)
        return builder.build()
    }

    private fun newPostalInsertForExisting(
        dataUri: Uri,
        rawContactId: Long,
        address: PostalAddress,
        isPrimary: Boolean
    ): ContentProviderOperation {
        val builder = ContentProviderOperation.newInsert(dataUri)
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
        dataUri: Uri,
        rawIdx: Int,
        org: Organization
    ): ContentProviderOperation {
        val builder = ContentProviderOperation.newInsert(dataUri)
            .withValueBackReference(Data.RAW_CONTACT_ID, rawIdx)
            .withValue(Data.MIMETYPE, CCOrganization.CONTENT_ITEM_TYPE)
        applyOrganizationPieces(builder, org)
        return builder.build()
    }

    private fun newOrganizationInsertForExisting(
        dataUri: Uri,
        rawContactId: Long,
        org: Organization
    ): ContentProviderOperation {
        val builder = ContentProviderOperation.newInsert(dataUri)
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

    private fun newNoteInsertWithBackRef(dataUri: Uri, rawIdx: Int, note: String): ContentProviderOperation =
        ContentProviderOperation.newInsert(dataUri)
            .withValueBackReference(Data.RAW_CONTACT_ID, rawIdx)
            .withValue(Data.MIMETYPE, Note.CONTENT_ITEM_TYPE)
            .withValue(Note.NOTE, note)
            .build()

    private fun newNoteInsertForExisting(dataUri: Uri, rawContactId: Long, note: String): ContentProviderOperation =
        ContentProviderOperation.newInsert(dataUri)
            .withValue(Data.RAW_CONTACT_ID, rawContactId)
            .withValue(Data.MIMETYPE, Note.CONTENT_ITEM_TYPE)
            .withValue(Note.NOTE, note)
            .build()

    // ---- Im ----

    private fun newImInsertWithBackRef(dataUri: Uri, rawIdx: Int, im: ImAccount): ContentProviderOperation {
        val builder = ContentProviderOperation.newInsert(dataUri)
            .withValueBackReference(Data.RAW_CONTACT_ID, rawIdx)
            .withValue(Data.MIMETYPE, Im.CONTENT_ITEM_TYPE)
            .withValue(Im.DATA, im.handle)
            .withValue(Im.TYPE, ImProtocolMapper.typeToAndroid(im.type))
        applyImProtocolColumns(builder, im)
        return builder.build()
    }

    private fun newImInsertForExisting(
        dataUri: Uri,
        rawContactId: Long,
        im: ImAccount
    ): ContentProviderOperation {
        val builder = ContentProviderOperation.newInsert(dataUri)
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

    private fun newPhotoInsertWithBackRef(dataUri: Uri, rawIdx: Int, photo: ContactPhoto): ContentProviderOperation =
        ContentProviderOperation.newInsert(dataUri)
            .withValueBackReference(Data.RAW_CONTACT_ID, rawIdx)
            .withValue(Data.MIMETYPE, Photo.CONTENT_ITEM_TYPE)
            .withValue(Photo.PHOTO, photo.data)
            .build()

    private fun newPhotoInsertForExisting(
        dataUri: Uri,
        rawContactId: Long,
        photo: ContactPhoto
    ): ContentProviderOperation =
        ContentProviderOperation.newInsert(dataUri)
            .withValue(Data.RAW_CONTACT_ID, rawContactId)
            .withValue(Data.MIMETYPE, Photo.CONTENT_ITEM_TYPE)
            .withValue(Photo.PHOTO, photo.data)
            .build()

    // ---- Delete ----

    // ---- GroupMembership ----

    private fun newGroupMembershipInsertWithBackRef(dataUri: Uri, rawIdx: Int, groupRowId: Long): ContentProviderOperation =
        ContentProviderOperation.newInsert(dataUri)
            .withValueBackReference(Data.RAW_CONTACT_ID, rawIdx)
            .withValue(Data.MIMETYPE, GroupMembership.CONTENT_ITEM_TYPE)
            .withValue(GroupMembership.GROUP_ROW_ID, groupRowId)
            .build()

    private fun newGroupMembershipInsertForExisting(dataUri: Uri, rawContactId: Long, groupRowId: Long): ContentProviderOperation =
        ContentProviderOperation.newInsert(dataUri)
            .withValue(Data.RAW_CONTACT_ID, rawContactId)
            .withValue(Data.MIMETYPE, GroupMembership.CONTENT_ITEM_TYPE)
            .withValue(GroupMembership.GROUP_ROW_ID, groupRowId)
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
