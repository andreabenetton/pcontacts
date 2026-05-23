// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.contactswriter

import android.provider.ContactsContract.CommonDataKinds.Im

/**
 * Maps vCard `IMPP:` URI schemes to ContactsContract's `Im.PROTOCOL_*`
 * fixed-tier constants. Unknown schemes fall back to PROTOCOL_CUSTOM
 * with the scheme itself written into Im.CUSTOM_PROTOCOL.
 *
 * Token recognition is case-insensitive. The mapping covers every
 * named PROTOCOL_* constant Android defines plus the common synonyms
 * (gtalk = googletalk, msnim = msn, ymsgr = yahoo).
 */
object ImProtocolMapper {

    fun fromScheme(scheme: String?): ImProtocol {
        if (scheme.isNullOrBlank()) return ImProtocol.CUSTOM
        return when (scheme.lowercase()) {
            "xmpp", "jabber" -> ImProtocol.JABBER
            "aim" -> ImProtocol.AIM
            "msn", "msnim" -> ImProtocol.MSN
            "yahoo", "ymsgr" -> ImProtocol.YAHOO
            "skype" -> ImProtocol.SKYPE
            "qq" -> ImProtocol.QQ
            "googletalk", "gtalk" -> ImProtocol.GOOGLE_TALK
            "icq" -> ImProtocol.ICQ
            "netmeeting" -> ImProtocol.NETMEETING
            else -> ImProtocol.CUSTOM
        }
    }

    fun toAndroid(protocol: ImProtocol): Int = when (protocol) {
        ImProtocol.JABBER -> Im.PROTOCOL_JABBER
        ImProtocol.AIM -> Im.PROTOCOL_AIM
        ImProtocol.MSN -> Im.PROTOCOL_MSN
        ImProtocol.YAHOO -> Im.PROTOCOL_YAHOO
        ImProtocol.SKYPE -> Im.PROTOCOL_SKYPE
        ImProtocol.QQ -> Im.PROTOCOL_QQ
        ImProtocol.GOOGLE_TALK -> Im.PROTOCOL_GOOGLE_TALK
        ImProtocol.ICQ -> Im.PROTOCOL_ICQ
        ImProtocol.NETMEETING -> Im.PROTOCOL_NETMEETING
        ImProtocol.CUSTOM -> Im.PROTOCOL_CUSTOM
    }

    fun typeToAndroid(type: ImAccountType): Int = when (type) {
        ImAccountType.HOME -> Im.TYPE_HOME
        ImAccountType.WORK -> Im.TYPE_WORK
        ImAccountType.OTHER -> Im.TYPE_OTHER
    }
}
