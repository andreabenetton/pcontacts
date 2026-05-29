// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.contactswriter

import android.content.ContentProviderClient
import android.database.Cursor
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Im
import android.provider.ContactsContract.CommonDataKinds.Note
import android.provider.ContactsContract.CommonDataKinds.Organization as CCOrganization
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.Photo
import android.provider.ContactsContract.CommonDataKinds.StructuredName as CCStructuredName
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.Data

/**
 * Reads all Data rows for a given RawContact and reconstructs a
 * [ContactRow]. This is the read-path inverse of [ContactsContractOps].
 *
 * The cursor-parsing step ([parse]) is split out from the provider
 * query for MatrixCursor-based testing, mirroring [RawContactReader].
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

    companion object {
        private val PROJECTION = arrayOf(
            Data.MIMETYPE,
            Data.DATA1, Data.DATA2, Data.DATA3, Data.DATA4,
            Data.DATA5, Data.DATA6, Data.DATA7, Data.DATA8,
            Data.DATA9, Data.DATA10, Data.DATA15,
            Data.IS_PRIMARY
        )

        fun parse(cursor: Cursor, sourceId: String): ContactRow? {
            if (cursor.count == 0) return null

            val mimeIdx = cursor.getColumnIndexOrThrow(Data.MIMETYPE)
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
            val primaryIdx = cursor.getColumnIndexOrThrow(Data.IS_PRIMARY)

            var displayName: String? = null
            var structuredName: StructuredName? = null
            val emails = mutableListOf<Pair<String, Boolean>>()
            val phones = mutableListOf<PhoneEntry>()
            val addresses = mutableListOf<PostalAddress>()
            var organization: Organization? = null
            val notes = mutableListOf<String>()
            val imAccounts = mutableListOf<ImAccount>()
            var photo: ContactPhoto? = null

            while (cursor.moveToNext()) {
                val mime = cursor.getString(mimeIdx) ?: continue
                val isPrimary = cursor.getInt(primaryIdx) == 1

                when (mime) {
                    CCStructuredName.CONTENT_ITEM_TYPE -> {
                        displayName = cursor.getString(d1)
                        val given = cursor.getString(d2)
                        val family = cursor.getString(d3)
                        val prefix = cursor.getString(d4)
                        val middle = cursor.getString(d5)
                        val suffix = cursor.getString(d6)
                        if (given != null || family != null || middle != null ||
                            prefix != null || suffix != null
                        ) {
                            structuredName = StructuredName(
                                given = given,
                                family = family,
                                middle = middle,
                                prefix = prefix,
                                suffix = suffix
                            )
                        }
                    }
                    Email.CONTENT_ITEM_TYPE -> {
                        val address = cursor.getString(d1)
                        if (address != null) emails += address to isPrimary
                    }
                    Phone.CONTENT_ITEM_TYPE -> {
                        val number = cursor.getString(d1)
                        val type = cursor.getInt(d2)
                        if (number != null) {
                            phones += PhoneEntry(
                                number = number,
                                type = PhoneTypeMapper.fromAndroid(type),
                                isPrimary = isPrimary
                            )
                        }
                    }
                    StructuredPostal.CONTENT_ITEM_TYPE -> {
                        addresses += PostalAddress(
                            street = cursor.getString(d4),
                            poBox = cursor.getString(d5),
                            neighborhood = cursor.getString(d6),
                            city = cursor.getString(d7),
                            region = cursor.getString(d8),
                            postcode = cursor.getString(d9),
                            country = cursor.getString(d10),
                            type = PostalAddressTypeMapper.fromAndroid(cursor.getInt(d2)),
                            isPrimary = isPrimary
                        )
                    }
                    CCOrganization.CONTENT_ITEM_TYPE -> {
                        organization = Organization(
                            company = cursor.getString(d1),
                            department = cursor.getString(d5),
                            title = cursor.getString(d4)
                        )
                    }
                    Note.CONTENT_ITEM_TYPE -> {
                        val note = cursor.getString(d1)
                        if (note != null) notes += note
                    }
                    Im.CONTENT_ITEM_TYPE -> {
                        val handle = cursor.getString(d1)
                        val protocol = cursor.getInt(d5)
                        val customProtocol = cursor.getString(d6)
                        val imType = cursor.getInt(d2)
                        if (handle != null) {
                            imAccounts += ImAccount(
                                handle = handle,
                                protocol = ImProtocolMapper.fromAndroid(protocol),
                                customProtocol = customProtocol,
                                type = ImProtocolMapper.typeFromAndroid(imType)
                            )
                        }
                    }
                    Photo.CONTENT_ITEM_TYPE -> {
                        val blob = cursor.getBlob(d15)
                        if (blob != null && blob.isNotEmpty()) {
                            photo = ContactPhoto(blob)
                        }
                    }
                }
            }

            // Primary-first ordering for emails
            val sortedEmails = emails.sortedByDescending { it.second }.map { it.first }

            if (sortedEmails.isEmpty() && phones.isEmpty() &&
                addresses.isEmpty() && imAccounts.isEmpty()
            ) {
                return null
            }

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
                photo = photo
            )
        }
    }
}
