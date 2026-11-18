// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.contactswriter

import android.Manifest
import android.accounts.Account
import android.content.ContentProviderClient
import android.provider.ContactsContract
import android.provider.ContactsContract.RawContacts
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * One contact too large to be handed to the provider does not stop the contacts around it
 * (review 2026-11-18): it used to fail its batch, and every batch after it was never applied.
 */
@RunWith(AndroidJUnit4::class)
class OversizedContactInstrumentedTest {

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.WRITE_CONTACTS
    )

    private lateinit var provider: ContentProviderClient
    private lateinit var account: Account

    @Before
    fun setUp() {
        provider = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
            .acquireContentProviderClient(ContactsContract.AUTHORITY)
            ?: throw AssertionError("Could not acquire ContentProviderClient for ContactsContract")
        account = Account("test-${UUID.randomUUID()}@proton.me", "io.pcontacts.test.instrumented")
    }

    @After
    fun tearDown() {
        provider.delete(
            SyncAdapterUri.decorate(RawContacts.CONTENT_URI, account.name, account.type),
            "${RawContacts.ACCOUNT_TYPE} = ? AND ${RawContacts.ACCOUNT_NAME} = ?",
            arrayOf(account.type, account.name)
        )
        provider.close()
    }

    @Test
    fun a_contact_binder_cannot_carry_is_left_out_and_the_others_are_written() {
        // Twice Binder's whole 1 MiB buffer in one note: no batch can carry this contact.
        val tooLarge = ContactRow("proton-huge", "Huge", emails = emptyList(), notes = listOf("n".repeat(NOTE_CHARS)))
        val intents = listOf(
            RawContactOpIntent.CreateContact(ContactRow("proton-before", "Before", emails = listOf("b@proton.me"))),
            RawContactOpIntent.CreateContact(tooLarge),
            RawContactOpIntent.CreateContact(ContactRow("proton-after", "After", emails = listOf("a@proton.me")))
        )

        val result = BatchApplier(provider).apply(account, intents)

        assertEquals(listOf("proton-huge"), result.tooLargeSourceIds)
        assertEquals(2, result.insertedContacts)
        val written = RawContactReader(provider).readExistingState(account).rowsBySourceId.keys
        assertEquals(setOf("proton-before", "proton-after"), written)
    }

    private companion object {
        const val NOTE_CHARS = 2 * 1024 * 1024
    }
}
