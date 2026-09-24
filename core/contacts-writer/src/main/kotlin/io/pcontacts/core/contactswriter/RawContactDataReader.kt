// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.contactswriter

import android.content.ContentProviderClient
import android.database.Cursor
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.GroupMembership
import android.provider.ContactsContract.CommonDataKinds.Im
import android.provider.ContactsContract.CommonDataKinds.Nickname
import android.provider.ContactsContract.CommonDataKinds.Note
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.Photo
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.CommonDataKinds.Website
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.CommonDataKinds.Organization as CCOrganization
import android.provider.ContactsContract.CommonDataKinds.StructuredName as CCStructuredName

/**
 * Reads all Data rows for a given RawContact and reconstructs a
 * [ContactRow]. This is the read-path inverse of [ContactsContractOps].
 *
 * The cursor-parsing step ([parse]) is split out from the provider
 * query for MatrixCursor-based testing, mirroring [RawContactReader].
 * [parseByRawContact] does the same for a cursor spanning many
 * RawContacts (the ADR-0023 scan), one [ContactRow] per raw.
 */
class RawContactDataReader(private val provider: ContentProviderClient) {

    fun read(rawContactId: Long, sourceId: String): ContactRow? {
        val cursor = provider.query(
            Data.CONTENT_URI,
            PROJECTION,
            "${Data.RAW_CONTACT_ID} = ?",
            arrayOf(rawContactId.toString()),
            null
        )
        return cursor?.use { parse(it, sourceId) }
    }

    /** The local Groups._ID values this RawContact is a member of (its GroupMembership rows). */
    fun readGroupRowIds(rawContactId: Long): List<Long> {
        val cursor = provider.query(
            Data.CONTENT_URI,
            arrayOf(GroupMembership.GROUP_ROW_ID),
            "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} = ?",
            arrayOf(rawContactId.toString(), GroupMembership.CONTENT_ITEM_TYPE),
            null
        ) ?: return emptyList()
        return cursor.use { c ->
            val ids = ArrayList<Long>(c.count)
            while (c.moveToNext()) if (!c.isNull(0)) ids += c.getLong(0)
            ids
        }
    }

    companion object {
        val PROJECTION = arrayOf(
            Data.MIMETYPE,
            Data.DATA1, Data.DATA2, Data.DATA3, Data.DATA4,
            Data.DATA5, Data.DATA6, Data.DATA7, Data.DATA8,
            Data.DATA9, Data.DATA10, Data.DATA15,
            Data.IS_PRIMARY
        )

        fun parse(cursor: Cursor, sourceId: String): ContactRow? {
            if (cursor.count == 0) return null
            val columns = Columns(cursor)
            val row = Accumulator()
            while (cursor.moveToNext()) row.add(cursor, columns)
            return row.build(sourceId)
        }

        /**
         * Splits a cursor that also carries `Data.RAW_CONTACT_ID` into
         * one row per RawContact. Rows are read-only diff input, so
         * `sourceId` is left blank; raws without a representable field
         * are absent from the result.
         */
        fun parseByRawContact(cursor: Cursor): Map<Long, ContactRow> {
            val columns = Columns(cursor)
            val rawIdx = cursor.getColumnIndexOrThrow(Data.RAW_CONTACT_ID)
            val rows = LinkedHashMap<Long, Accumulator>()
            while (cursor.moveToNext()) {
                rows.getOrPut(cursor.getLong(rawIdx)) { Accumulator() }.add(cursor, columns)
            }
            return rows.mapNotNull { (id, row) -> row.build(sourceId = "")?.let { id to it } }.toMap()
        }
    }

    private class Columns(cursor: Cursor) {
        val mime = cursor.getColumnIndexOrThrow(Data.MIMETYPE)
        val d1 = cursor.getColumnIndexOrThrow(Data.DATA1)
        val d2 = cursor.getColumnIndexOrThrow(Data.DATA2)
        val d3 = cursor.getColumnIndexOrThrow(Data.DATA3)
        val d4 = cursor.getColumnIndexOrThrow(Data.DATA4)
        val d5 = cursor.getColumnIndexOrThrow(Data.DATA5)
        val d6 = cursor.getColumnIndexOrThrow(Data.DATA6)
        val d7 = cursor.getColumnIndexOrThrow(Data.DATA7)
        val d8 = cursor.getColumnIndexOrThrow(Data.DATA8)
        val d9 = cursor.getColumnIndexOrThrow(Data.DATA9)
        val d10 = cursor.getColumnIndexOrThrow(Data.DATA10)
        val d15 = cursor.getColumnIndexOrThrow(Data.DATA15)
        val primary = cursor.getColumnIndexOrThrow(Data.IS_PRIMARY)
    }

    /** Collects one RawContact's Data rows; [build] applies the ContactRow guard. */
    private class Accumulator {
        private var displayName: String? = null
        private var structuredName: StructuredName? = null
        private val emails = mutableListOf<Pair<String, Boolean>>()
        private val phones = mutableListOf<PhoneEntry>()
        private val addresses = mutableListOf<PostalAddress>()
        private var organization: Organization? = null
        private val notes = mutableListOf<String>()
        private val imAccounts = mutableListOf<ImAccount>()
        private var birthday: String? = null
        private var anniversary: String? = null
        private val nicknames = mutableListOf<String>()
        private val websites = mutableListOf<String>()
        private var photo: ContactPhoto? = null

        fun add(cursor: Cursor, c: Columns) {
            val isPrimary = cursor.getInt(c.primary) == 1
            when (cursor.getString(c.mime) ?: return) {
                CCStructuredName.CONTENT_ITEM_TYPE -> addName(cursor, c)
                Email.CONTENT_ITEM_TYPE -> cursor.getString(c.d1)?.let { emails += it to isPrimary }
                Phone.CONTENT_ITEM_TYPE -> cursor.getString(c.d1)?.let { number ->
                    phones += PhoneEntry(
                        number = number,
                        type = PhoneTypeMapper.fromAndroid(cursor.getInt(c.d2)),
                        isPrimary = isPrimary
                    )
                }
                StructuredPostal.CONTENT_ITEM_TYPE -> addresses += PostalAddress(
                    street = cursor.getString(c.d4),
                    poBox = cursor.getString(c.d5),
                    neighborhood = cursor.getString(c.d6),
                    city = cursor.getString(c.d7),
                    region = cursor.getString(c.d8),
                    postcode = cursor.getString(c.d9),
                    country = cursor.getString(c.d10),
                    type = PostalAddressTypeMapper.fromAndroid(cursor.getInt(c.d2)),
                    isPrimary = isPrimary
                )
                CCOrganization.CONTENT_ITEM_TYPE -> organization = Organization(
                    company = cursor.getString(c.d1),
                    department = cursor.getString(c.d5),
                    title = cursor.getString(c.d4)
                )
                Note.CONTENT_ITEM_TYPE -> cursor.getString(c.d1)?.let { notes += it }
                Im.CONTENT_ITEM_TYPE -> cursor.getString(c.d1)?.let { handle ->
                    imAccounts += ImAccount(
                        handle = handle,
                        protocol = ImProtocolMapper.fromAndroid(cursor.getInt(c.d5)),
                        customProtocol = cursor.getString(c.d6),
                        type = ImProtocolMapper.typeFromAndroid(cursor.getInt(c.d2))
                    )
                }
                Event.CONTENT_ITEM_TYPE, Nickname.CONTENT_ITEM_TYPE, Website.CONTENT_ITEM_TYPE ->
                    addExtra(cursor.getString(c.mime), cursor, c)
                Photo.CONTENT_ITEM_TYPE -> {
                    val blob = cursor.getBlob(c.d15)
                    if (blob != null && blob.isNotEmpty()) photo = ContactPhoto(blob)
                }
            }
        }

        /**
         * Event, Nickname and Website rows (ADR-0023, 2026-09-24). Only the two
         * event kinds Proton has a property for are carried; other or custom
         * events are not.
         */
        private fun addExtra(mime: String, cursor: Cursor, c: Columns) {
            val value = cursor.getString(c.d1)?.trim()?.takeIf { it.isNotEmpty() } ?: return
            when {
                mime == Nickname.CONTENT_ITEM_TYPE -> nicknames += value
                mime == Website.CONTENT_ITEM_TYPE -> websites += value
                cursor.getInt(c.d2) == Event.TYPE_BIRTHDAY -> birthday = value
                cursor.getInt(c.d2) == Event.TYPE_ANNIVERSARY -> anniversary = value
            }
        }

        private fun addName(cursor: Cursor, c: Columns) {
            displayName = cursor.getString(c.d1)
            val given = cursor.getString(c.d2)
            val family = cursor.getString(c.d3)
            val prefix = cursor.getString(c.d4)
            val middle = cursor.getString(c.d5)
            val suffix = cursor.getString(c.d6)
            if (listOf(given, family, middle, prefix, suffix).any { it != null }) {
                structuredName = StructuredName(
                    given = given,
                    family = family,
                    middle = middle,
                    prefix = prefix,
                    suffix = suffix
                )
            }
        }

        fun build(sourceId: String): ContactRow? {
            // Primary-first ordering for emails
            val sortedEmails = emails.sortedByDescending { it.second }.map { it.first }
            val hasName = !displayName.isNullOrBlank() || structuredName != null
            val hasField = listOf(sortedEmails, phones, addresses, imAccounts, notes, nicknames, websites)
                .any { it.isNotEmpty() } ||
                organization != null || photo != null || birthday != null || anniversary != null
            if (!hasName && !hasField) return null
            return ContactRow(
                sourceId = sourceId,
                displayName = displayName,
                structuredName = structuredName,
                emails = sortedEmails,
                phones = phones,
                addresses = addresses,
                organization = organization,
                notes = notes,
                imAccounts = imAccounts,
                birthday = birthday,
                anniversary = anniversary,
                nicknames = nicknames,
                websites = websites,
                photo = photo
            )
        }
    }
}
