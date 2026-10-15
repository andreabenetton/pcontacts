// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.contactswriter

import android.provider.ContactsContract.CommonDataKinds.Email

/**
 * Maps vCard `EMAIL;TYPE=...` tokens to [EmailType] and to `Email.TYPE_*` (ADR-0023, 2026-10-15).
 * `[V]` Proton's editor offers no type, `home`, `work` and `other` (WebClients
 * `helpers/contacts.ts`, `getTypeValues`); no type and `other` are both [EmailType.OTHER], and so
 * is any Android type other than home and work. Tokens are compared case-insensitively.
 */
object EmailTypeMapper {

    fun fromTokens(tokens: List<String>): EmailType {
        val lower = tokens.map { it.lowercase() }
        return when {
            "home" in lower -> EmailType.HOME
            "work" in lower -> EmailType.WORK
            else -> EmailType.OTHER
        }
    }

    fun toTokens(type: EmailType): List<String> = when (type) {
        EmailType.HOME -> listOf("home")
        EmailType.WORK -> listOf("work")
        EmailType.OTHER -> emptyList()
    }

    fun toAndroid(type: EmailType): Int = when (type) {
        EmailType.HOME -> Email.TYPE_HOME
        EmailType.WORK -> Email.TYPE_WORK
        EmailType.OTHER -> Email.TYPE_OTHER
    }

    fun fromAndroid(type: Int): EmailType = when (type) {
        Email.TYPE_HOME -> EmailType.HOME
        Email.TYPE_WORK -> EmailType.WORK
        else -> EmailType.OTHER
    }
}
