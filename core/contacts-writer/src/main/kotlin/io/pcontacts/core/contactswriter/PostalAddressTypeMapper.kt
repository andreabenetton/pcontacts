// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.contactswriter

import android.provider.ContactsContract.CommonDataKinds.StructuredPostal

/**
 * Maps vCard `ADR;TYPE=...` tokens to the MVP `PostalAddressType` enum
 * and then to `ContactsContract.CommonDataKinds.StructuredPostal.TYPE_*`.
 *
 * Mirrors the shape of PhoneTypeMapper. Token matching is
 * case-insensitive; unknown tokens collapse to `OTHER`.
 */
object PostalAddressTypeMapper {

    fun fromTokens(rawTokens: List<String>): PostalAddressType {
        val tokens = rawTokens.asSequence().map { it.lowercase() }.toSet()
        return when {
            "home" in tokens -> PostalAddressType.HOME
            "work" in tokens -> PostalAddressType.WORK
            else -> PostalAddressType.OTHER
        }
    }

    fun toAndroid(type: PostalAddressType): Int = when (type) {
        PostalAddressType.HOME -> StructuredPostal.TYPE_HOME
        PostalAddressType.WORK -> StructuredPostal.TYPE_WORK
        PostalAddressType.OTHER -> StructuredPostal.TYPE_OTHER
    }

    fun fromAndroid(type: Int): PostalAddressType = when (type) {
        StructuredPostal.TYPE_HOME -> PostalAddressType.HOME
        StructuredPostal.TYPE_WORK -> PostalAddressType.WORK
        else -> PostalAddressType.OTHER
    }
}
