// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.contactswriter

import android.accounts.Account
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.Relation
import android.provider.ContactsContract.CommonDataKinds.SipAddress
import android.provider.ContactsContract.RawContacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class OrphanContactMoverTest {

    private val proton = Account("alice@proton.me", "io.pcontacts.account")

    @Test fun only_the_exact_phone_account_is_an_orphan() {
        assertTrue(OrphanPhoneAccount.matches("PHONE", "PHONE"))
        assertFalse(OrphanPhoneAccount.matches("vnd.sec.contact.phone", "vnd.sec.contact.phone"))
        assertFalse(OrphanPhoneAccount.matches("PHONE", "Telefono"))
        assertFalse(OrphanPhoneAccount.matches(null, null))
    }

    @Test fun the_move_is_one_sync_adapter_update_scoped_to_the_orphan_row() {
        val op = ContactsContractOps.buildMoveOrphan(proton, rawContactId = 871L)

        assertTrue(op.isUpdate)
        // The source account on the URI: the provider adds it to the selection (ADR-0026).
        assertEquals("true", op.uri.getQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER))
        assertEquals("PHONE", op.uri.getQueryParameter(RawContacts.ACCOUNT_TYPE))
        assertEquals("PHONE", op.uri.getQueryParameter(RawContacts.ACCOUNT_NAME))
        val values = op.resolveValueBackReferences(emptyArray(), 0)!!
        assertEquals("io.pcontacts.account", values.getAsString(RawContacts.ACCOUNT_TYPE))
        assertEquals("alice@proton.me", values.getAsString(RawContacts.ACCOUNT_NAME))
        assertTrue(values.containsKey(RawContacts.SOURCE_ID))
        assertNull(values.getAsString(RawContacts.SOURCE_ID))
        assertNull(values.getAsString(RawContacts.SYNC1))
        assertEquals(1, values.getAsInteger(RawContacts.DIRTY))
        assertEquals(
            listOf("871", "PHONE", "PHONE"),
            op.resolveSelectionArgsBackReferences(emptyArray(), 0)!!.toList()
        )
    }

    @Test fun events_other_than_birthday_and_anniversary_relations_and_sip_are_not_carried() {
        val kinds = OrphanContactMover.uncarriedKinds(
            listOf(
                Phone.CONTENT_ITEM_TYPE to Phone.TYPE_MOBILE,
                Event.CONTENT_ITEM_TYPE to Event.TYPE_BIRTHDAY,
                Event.CONTENT_ITEM_TYPE to Event.TYPE_OTHER,
                Event.CONTENT_ITEM_TYPE to Event.TYPE_CUSTOM,
                Relation.CONTENT_ITEM_TYPE to Relation.TYPE_SPOUSE,
                SipAddress.CONTENT_ITEM_TYPE to null,
                "vnd.com.whatsapp.profile" to null
            )
        )
        assertEquals(listOf(UncarriedKind.EVENT, UncarriedKind.RELATION, UncarriedKind.SIP_ADDRESS), kinds)
    }
}
