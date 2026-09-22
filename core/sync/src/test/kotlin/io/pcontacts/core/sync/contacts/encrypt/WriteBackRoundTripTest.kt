// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.core.sync.contacts.encrypt

import io.pcontacts.core.contactswriter.PhoneEntry
import io.pcontacts.core.contactswriter.PhoneType
import io.pcontacts.core.crypto.openpgp.BouncyCastleOpenPgpService
import io.pcontacts.core.proton.api.contacts.ContactCardDto
import io.pcontacts.core.proton.api.contacts.ContactDto
import io.pcontacts.core.protoncontacts.CardEncryptOp
import io.pcontacts.core.protoncontacts.CardEncryptRequest
import io.pcontacts.core.protoncontacts.CardType
import io.pcontacts.core.protoncontacts.ContactDecrypter
import io.pcontacts.core.protoncontacts.ContactPatch
import io.pcontacts.core.protoncontacts.ContactProcessor
import io.pcontacts.core.protoncontacts.ContactSerializer
import io.pcontacts.core.sync.contacts.DecryptedContactToRow
import io.pcontacts.core.sync.contacts.RowToDecryptedContact
import io.pcontacts.core.sync.contacts.decrypt.OpenPgpCardCryptoOp
import io.pcontacts.core.sync.contacts.decrypt.TestKeys
import io.pcontacts.core.sync.contacts.merge.MergeBaseCodec
import io.pcontacts.core.sync.contacts.merge.ThreeWayMerger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The invariant the review asked for, end to end under real PGP: a rich
 * Proton contact → decrypt → ContactsContract row → one unrelated local
 * edit → three-way merge → change set → patched cards → decrypt again,
 * and every property the app does not model is still there.
 */
class WriteBackRoundTripTest {

    private lateinit var encryptOp: CardEncryptOp
    private lateinit var serializer: ContactSerializer
    private lateinit var processor: ContactProcessor

    @Before fun setUp() {
        val openPgp = BouncyCastleOpenPgpService()
        val (_, unlocked) = TestKeys.armoredAndUnlocked("writeback".toCharArray())
        encryptOp = OpenPgpCardEncryptOp.build(openPgp, listOf(unlocked.public), unlocked.private)
        serializer = ContactSerializer(encryptOp)
        processor = ContactProcessor(
            ContactDecrypter(
                OpenPgpCardCryptoOp.build(openPgp, unlocked.allPrivateKeys, listOf(unlocked.public))
            )
        )
    }

    private val signedText = """
        BEGIN:VCARD
        VERSION:4.0
        PRODID:-//ProtonMail//ProtonMail vCard 1.0.0//EN
        FN:Alice Example
        UID:proton-web-uid
        item1.EMAIL;PREF=1:alice@example.com
        item1.KEY:data:application/pgp-keys;base64,AAAA
        item1.X-PM-ENCRYPT:true
        END:VCARD
    """.trimIndent()

    private val encryptedText = """
        BEGIN:VCARD
        VERSION:4.0
        N:Example;Alice;Marie,Jane;Dr.;PhD
        TEL;TYPE=cell,voice;PREF=1:+15550001
        BDAY:19800101
        URL:https://alice.example
        NICKNAME:Ali
        X-ABLabel:Custom
        NOTE:Keep me
        END:VCARD
    """.trimIndent()

    private fun serverDto(): ContactDto {
        val signed = encryptOp(CardEncryptRequest.SignOnly(signedText))
        val encrypted = encryptOp(CardEncryptRequest.EncryptAndSign(encryptedText))
        return ContactDto(
            id = "ct-rich",
            uid = "proton-web-uid",
            cards = listOf(
                ContactCardDto(type = CardType.SIGNED.wireValue, data = signed.data, signature = signed.signature),
                ContactCardDto(
                    type = CardType.ENCRYPTED_AND_SIGNED.wireValue,
                    data = encrypted.data,
                    signature = encrypted.signature
                )
            )
        )
    }

    @Test fun an_unrelated_phone_edit_keeps_every_unmodelled_property_and_parameter() {
        val server = processor.process(serverDto())
        val serverCanonical = MergeBaseCodec.canonical(server)!!

        // The pull wrote the row; the user changes the phone number on the phone.
        val row = DecryptedContactToRow.convert(server)!!
        val edited = row.copy(phones = listOf(PhoneEntry("+15559999", PhoneType.MOBILE, isPrimary = true)))
        val local = RowToDecryptedContact.convert(edited, server.protonContactId, server.protonUid)

        val merged = ThreeWayMerger.merge(ThreeWayMerger.MergeInput(serverCanonical, serverCanonical, local))
        val payload = (merged as ThreeWayMerger.MergeResult.AutoMerged).merged
        val patch = ContactPatch.diff(serverCanonical, payload)
        val cards = serializer.serialize(server.cards, patch, fallbackUid = "unused")

        val result = processor.process(serverDto().copy(cards = cards))

        assertTrue(result.verified)
        assertEquals("proton-web-uid", result.protonUid)
        assertEquals(listOf("+15559999"), result.phones.map { it.number })
        assertEquals(listOf("Marie", "Jane"), result.structuredName?.additionalNames)
        val signedBack = result.cards.first { it.originalType == CardType.SIGNED }.plaintext
        val encryptedBack = result.cards.first { it.originalType == CardType.ENCRYPTED_AND_SIGNED }.plaintext
        assertTrue(signedBack.contains("item1.KEY:"))
        assertTrue(signedBack.contains("item1.X-PM-ENCRYPT:true"))
        assertTrue(signedBack.contains("PREF=1"))
        assertTrue(encryptedBack.contains("BDAY:19800101"))
        assertTrue(encryptedBack.contains("URL:https://alice.example"))
        assertTrue(encryptedBack.contains("NICKNAME:Ali"))
        assertTrue(encryptedBack.contains("X-ABLabel:Custom"))
        assertTrue(encryptedBack.contains("NOTE:Keep me"))
        assertTrue(!encryptedBack.contains("+15550001"))
    }
}
