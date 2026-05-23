// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.contactswriter

import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class PostalAddressTypeMapperTest {

    @Test fun home_and_work_tokens_map_to_their_TYPE_constants() {
        assertEquals(PostalAddressType.HOME, PostalAddressTypeMapper.fromTokens(listOf("home")))
        assertEquals(PostalAddressType.WORK, PostalAddressTypeMapper.fromTokens(listOf("work")))
    }

    @Test fun unknown_or_empty_tokens_collapse_to_OTHER() {
        assertEquals(PostalAddressType.OTHER, PostalAddressTypeMapper.fromTokens(emptyList()))
        assertEquals(PostalAddressType.OTHER, PostalAddressTypeMapper.fromTokens(listOf("postal")))
    }

    @Test fun token_matching_is_case_insensitive() {
        assertEquals(PostalAddressType.HOME, PostalAddressTypeMapper.fromTokens(listOf("HOME")))
        assertEquals(PostalAddressType.WORK, PostalAddressTypeMapper.fromTokens(listOf("Work")))
    }

    @Test fun toAndroid_maps_every_enum_member_to_its_ContactsContract_constant() {
        assertEquals(StructuredPostal.TYPE_HOME, PostalAddressTypeMapper.toAndroid(PostalAddressType.HOME))
        assertEquals(StructuredPostal.TYPE_WORK, PostalAddressTypeMapper.toAndroid(PostalAddressType.WORK))
        assertEquals(StructuredPostal.TYPE_OTHER, PostalAddressTypeMapper.toAndroid(PostalAddressType.OTHER))
    }
}
