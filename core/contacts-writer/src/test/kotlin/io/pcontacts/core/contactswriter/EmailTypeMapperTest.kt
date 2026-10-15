// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.contactswriter

import android.provider.ContactsContract.CommonDataKinds.Email
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class EmailTypeMapperTest {

    @Test fun home_and_work_tokens_map_whatever_their_case() {
        assertEquals(EmailType.HOME, EmailTypeMapper.fromTokens(listOf("HOME")))
        assertEquals(EmailType.WORK, EmailTypeMapper.fromTokens(listOf("internet", "work")))
    }

    @Test fun no_type_and_other_are_both_other() {
        assertEquals(EmailType.OTHER, EmailTypeMapper.fromTokens(emptyList()))
        assertEquals(EmailType.OTHER, EmailTypeMapper.fromTokens(listOf("other")))
    }

    @Test fun every_type_round_trips_through_its_tokens_and_android() {
        EmailType.entries.forEach { type ->
            assertEquals(type, EmailTypeMapper.fromTokens(EmailTypeMapper.toTokens(type)))
            assertEquals(type, EmailTypeMapper.fromAndroid(EmailTypeMapper.toAndroid(type)))
        }
    }

    @Test fun android_types_without_a_proton_match_are_other() {
        assertEquals(EmailType.OTHER, EmailTypeMapper.fromAndroid(Email.TYPE_MOBILE))
        assertEquals(EmailType.OTHER, EmailTypeMapper.fromAndroid(Email.TYPE_CUSTOM))
    }
}
