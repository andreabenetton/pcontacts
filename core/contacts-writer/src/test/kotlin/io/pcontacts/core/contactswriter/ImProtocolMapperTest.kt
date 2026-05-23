// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.contactswriter

import android.provider.ContactsContract.CommonDataKinds.Im
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ImProtocolMapperTest {

    @Test fun xmpp_and_jabber_schemes_both_map_to_JABBER() {
        assertEquals(ImProtocol.JABBER, ImProtocolMapper.fromScheme("xmpp"))
        assertEquals(ImProtocol.JABBER, ImProtocolMapper.fromScheme("jabber"))
    }

    @Test fun msn_synonyms_map_to_MSN() {
        assertEquals(ImProtocol.MSN, ImProtocolMapper.fromScheme("msn"))
        assertEquals(ImProtocol.MSN, ImProtocolMapper.fromScheme("msnim"))
    }

    @Test fun yahoo_synonyms_map_to_YAHOO() {
        assertEquals(ImProtocol.YAHOO, ImProtocolMapper.fromScheme("yahoo"))
        assertEquals(ImProtocol.YAHOO, ImProtocolMapper.fromScheme("ymsgr"))
    }

    @Test fun google_talk_synonyms_map_to_GOOGLE_TALK() {
        assertEquals(ImProtocol.GOOGLE_TALK, ImProtocolMapper.fromScheme("googletalk"))
        assertEquals(ImProtocol.GOOGLE_TALK, ImProtocolMapper.fromScheme("gtalk"))
    }

    @Test fun core_named_protocols_each_map_to_their_constant() {
        assertEquals(ImProtocol.AIM, ImProtocolMapper.fromScheme("aim"))
        assertEquals(ImProtocol.SKYPE, ImProtocolMapper.fromScheme("skype"))
        assertEquals(ImProtocol.QQ, ImProtocolMapper.fromScheme("qq"))
        assertEquals(ImProtocol.ICQ, ImProtocolMapper.fromScheme("icq"))
        assertEquals(ImProtocol.NETMEETING, ImProtocolMapper.fromScheme("netmeeting"))
    }

    @Test fun null_and_blank_schemes_collapse_to_CUSTOM() {
        assertEquals(ImProtocol.CUSTOM, ImProtocolMapper.fromScheme(null))
        assertEquals(ImProtocol.CUSTOM, ImProtocolMapper.fromScheme(""))
        assertEquals(ImProtocol.CUSTOM, ImProtocolMapper.fromScheme("  "))
    }

    @Test fun unknown_schemes_fall_back_to_CUSTOM_for_caller_label_handling() {
        assertEquals(ImProtocol.CUSTOM, ImProtocolMapper.fromScheme("matrix"))
        assertEquals(ImProtocol.CUSTOM, ImProtocolMapper.fromScheme("signal"))
    }

    @Test fun scheme_matching_is_case_insensitive() {
        assertEquals(ImProtocol.JABBER, ImProtocolMapper.fromScheme("XMPP"))
        assertEquals(ImProtocol.SKYPE, ImProtocolMapper.fromScheme("Skype"))
    }

    @Test fun toAndroid_maps_every_protocol_enum_member_to_its_PROTOCOL_constant() {
        assertEquals(Im.PROTOCOL_JABBER, ImProtocolMapper.toAndroid(ImProtocol.JABBER))
        assertEquals(Im.PROTOCOL_AIM, ImProtocolMapper.toAndroid(ImProtocol.AIM))
        assertEquals(Im.PROTOCOL_MSN, ImProtocolMapper.toAndroid(ImProtocol.MSN))
        assertEquals(Im.PROTOCOL_YAHOO, ImProtocolMapper.toAndroid(ImProtocol.YAHOO))
        assertEquals(Im.PROTOCOL_SKYPE, ImProtocolMapper.toAndroid(ImProtocol.SKYPE))
        assertEquals(Im.PROTOCOL_QQ, ImProtocolMapper.toAndroid(ImProtocol.QQ))
        assertEquals(Im.PROTOCOL_GOOGLE_TALK, ImProtocolMapper.toAndroid(ImProtocol.GOOGLE_TALK))
        assertEquals(Im.PROTOCOL_ICQ, ImProtocolMapper.toAndroid(ImProtocol.ICQ))
        assertEquals(Im.PROTOCOL_NETMEETING, ImProtocolMapper.toAndroid(ImProtocol.NETMEETING))
        assertEquals(Im.PROTOCOL_CUSTOM, ImProtocolMapper.toAndroid(ImProtocol.CUSTOM))
    }

    @Test fun typeToAndroid_maps_HOME_WORK_OTHER_to_TYPE_constants() {
        assertEquals(Im.TYPE_HOME, ImProtocolMapper.typeToAndroid(ImAccountType.HOME))
        assertEquals(Im.TYPE_WORK, ImProtocolMapper.typeToAndroid(ImAccountType.WORK))
        assertEquals(Im.TYPE_OTHER, ImProtocolMapper.typeToAndroid(ImAccountType.OTHER))
    }
}
