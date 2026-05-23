// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.contactswriter

import android.provider.ContactsContract.CommonDataKinds.Phone
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class PhoneTypeMapperTest {

    @Test fun home_token_maps_to_HOME() {
        assertEquals(PhoneType.HOME, PhoneTypeMapper.fromTokens(listOf("home")))
    }

    @Test fun work_token_maps_to_WORK() {
        assertEquals(PhoneType.WORK, PhoneTypeMapper.fromTokens(listOf("work")))
    }

    @Test fun cell_and_mobile_tokens_both_map_to_MOBILE() {
        assertEquals(PhoneType.MOBILE, PhoneTypeMapper.fromTokens(listOf("cell")))
        assertEquals(PhoneType.MOBILE, PhoneTypeMapper.fromTokens(listOf("mobile")))
    }

    @Test fun fax_plus_home_maps_to_FAX_HOME() {
        assertEquals(PhoneType.FAX_HOME, PhoneTypeMapper.fromTokens(listOf("fax", "home")))
        // Order shouldn't matter.
        assertEquals(PhoneType.FAX_HOME, PhoneTypeMapper.fromTokens(listOf("home", "fax")))
    }

    @Test fun fax_plus_work_maps_to_FAX_WORK() {
        assertEquals(PhoneType.FAX_WORK, PhoneTypeMapper.fromTokens(listOf("fax", "work")))
    }

    @Test fun bare_fax_with_no_modifier_falls_back_to_OTHER() {
        // Android lacks a "generic fax" TYPE — _HOME / _WORK are the only fax variants.
        assertEquals(PhoneType.OTHER, PhoneTypeMapper.fromTokens(listOf("fax")))
    }

    @Test fun pager_and_main_tokens_each_have_their_own_TYPE() {
        assertEquals(PhoneType.PAGER, PhoneTypeMapper.fromTokens(listOf("pager")))
        assertEquals(PhoneType.MAIN, PhoneTypeMapper.fromTokens(listOf("main")))
    }

    @Test fun unknown_tokens_collapse_to_OTHER() {
        assertEquals(PhoneType.OTHER, PhoneTypeMapper.fromTokens(listOf("whatsapp")))
        assertEquals(PhoneType.OTHER, PhoneTypeMapper.fromTokens(listOf("voice")))
        assertEquals(PhoneType.OTHER, PhoneTypeMapper.fromTokens(emptyList()))
    }

    @Test fun token_matching_is_case_insensitive() {
        assertEquals(PhoneType.HOME, PhoneTypeMapper.fromTokens(listOf("HOME")))
        assertEquals(PhoneType.WORK, PhoneTypeMapper.fromTokens(listOf("Work")))
        assertEquals(PhoneType.FAX_HOME, PhoneTypeMapper.fromTokens(listOf("FAX", "Home")))
    }

    @Test fun toAndroid_maps_every_enum_member_to_its_ContactsContract_TYPE_constant() {
        assertEquals(Phone.TYPE_HOME, PhoneTypeMapper.toAndroid(PhoneType.HOME))
        assertEquals(Phone.TYPE_WORK, PhoneTypeMapper.toAndroid(PhoneType.WORK))
        assertEquals(Phone.TYPE_MOBILE, PhoneTypeMapper.toAndroid(PhoneType.MOBILE))
        assertEquals(Phone.TYPE_FAX_HOME, PhoneTypeMapper.toAndroid(PhoneType.FAX_HOME))
        assertEquals(Phone.TYPE_FAX_WORK, PhoneTypeMapper.toAndroid(PhoneType.FAX_WORK))
        assertEquals(Phone.TYPE_PAGER, PhoneTypeMapper.toAndroid(PhoneType.PAGER))
        assertEquals(Phone.TYPE_MAIN, PhoneTypeMapper.toAndroid(PhoneType.MAIN))
        assertEquals(Phone.TYPE_OTHER, PhoneTypeMapper.toAndroid(PhoneType.OTHER))
    }
}
